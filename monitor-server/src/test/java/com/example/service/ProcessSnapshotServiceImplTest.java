package com.example.service;

import com.example.config.SseEventBus;
import com.example.entity.vo.request.ProcessSnapshotVO;
import com.example.entity.vo.response.AlertHistoryVO;
import com.example.entity.vo.response.GpuSnapshotResponseVO;
import com.example.entity.vo.response.ProcessSnapshotResponseVO;
import com.example.entity.vo.response.SmartSnapshotResponseVO;
import com.example.entity.vo.response.SystemdSnapshotResponseVO;
import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.service.impl.ProcessSnapshotServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link ProcessSnapshotServiceImpl} 单元测试。
 * <p>
 * 沿用项目惯例：使用手写 fake SseEventBus 替代 Mockito，避免 JVM attach 依赖。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>ingest 后立即 getLatest 命中（写入 + 读取链路）；</li>
 *   <li>getLatest 对未上报客户端返回 null；</li>
 *   <li>ingest 触发 SSE publishProcessSnapshot 一次（载荷字段对齐请求 VO）；</li>
 *   <li>ingest 对 null clientId / null vo 安全返回；</li>
 *   <li>SSE publish 抛异常不影响 cache 写入；</li>
 *   <li>watchedPatterns null 入参 → 响应 VO 内为空 Map。</li>
 * </ul>
 */
class ProcessSnapshotServiceImplTest {

    private ProcessSnapshotServiceImpl service;
    private CapturingSseBus sseBus;

    @BeforeEach
    void setUp() {
        service = new ProcessSnapshotServiceImpl();
        sseBus = new CapturingSseBus();
        ReflectionTestUtils.setField(service, "sseEventBus", sseBus);
    }

    @Test
    void ingestAndGetLatest_shouldRoundTrip() {
        ProcessSnapshotVO vo = sampleVo();
        service.ingest(100, vo);
        ProcessSnapshotResponseVO result = service.getLatest(100);
        Assertions.assertNotNull(result);
        Assertions.assertEquals(100, result.getClientId());
        Assertions.assertNotNull(result.getUpdatedAt());
        Assertions.assertEquals(vo.getTimestamp(), result.getTimestamp());
        Assertions.assertEquals(2, result.getTop10ByCpu().size());
        Assertions.assertEquals("java", result.getTop10ByCpu().get(0).getName());
        Assertions.assertEquals(1234, result.getTop10ByCpu().get(0).getPid());
        Assertions.assertEquals(0.42, result.getTop10ByCpu().get(0).getCpuPercent(), 1e-6);
    }

    @Test
    void getLatest_shouldReturnNullForUnknownClient() {
        Assertions.assertNull(service.getLatest(9999));
    }

    @Test
    void getLatest_shouldReturnNullForNullClientId() {
        Assertions.assertNull(service.getLatest(null));
    }

    @Test
    void ingest_shouldPublishSseEventWithSnapshot() {
        ProcessSnapshotVO vo = sampleVo();
        service.ingest(200, vo);
        Assertions.assertEquals(1, sseBus.processPublishes.size());
        CapturedPublish published = sseBus.processPublishes.get(0);
        Assertions.assertEquals(200, published.clientId);
        Assertions.assertNotNull(published.vo);
        Assertions.assertEquals(200, published.vo.getClientId());
        Assertions.assertEquals(2, published.vo.getTop10ByCpu().size());
    }

    @Test
    void ingest_shouldIgnoreNullClientId() {
        service.ingest(null, sampleVo());
        Assertions.assertTrue(sseBus.processPublishes.isEmpty());
        Assertions.assertNull(service.getLatest(null));
    }

    @Test
    void ingest_shouldIgnoreNullVo() {
        service.ingest(300, null);
        Assertions.assertNull(service.getLatest(300));
        Assertions.assertTrue(sseBus.processPublishes.isEmpty());
    }

    @Test
    void ingest_shouldSurviveSsePublishFailure() {
        AtomicReference<Boolean> publishCalled = new AtomicReference<>(false);
        // 注入一个会抛异常的 sseBus
        ReflectionTestUtils.setField(service, "sseEventBus", new ThrowingSseBus(publishCalled));
        ProcessSnapshotVO vo = sampleVo();
        service.ingest(400, vo);
        Assertions.assertTrue(publishCalled.get(), "SSE 推送应被尝试");
        // cache 仍然应写入
        ProcessSnapshotResponseVO latest = service.getLatest(400);
        Assertions.assertNotNull(latest, "SSE 推送失败不应影响 cache");
        Assertions.assertEquals(400, latest.getClientId());
    }

    @Test
    void ingest_shouldHandleNullWatchedPatterns() {
        ProcessSnapshotVO vo = new ProcessSnapshotVO();
        vo.setTimestamp(System.currentTimeMillis());
        vo.setTop10ByCpu(new ArrayList<>());
        vo.setTop10ByMemory(new ArrayList<>());
        vo.setWatchedPatterns(null);
        service.ingest(500, vo);
        ProcessSnapshotResponseVO latest = service.getLatest(500);
        Assertions.assertNotNull(latest);
        Assertions.assertNotNull(latest.getWatchedPatterns());
        Assertions.assertTrue(latest.getWatchedPatterns().isEmpty());
    }

    @Test
    void ingest_shouldPreserveWatchedPatternsContent() {
        ProcessSnapshotVO vo = sampleVo();
        Map<String, Boolean> watched = new LinkedHashMap<>();
        watched.put("^java$", true);
        watched.put("^missing$", false);
        vo.setWatchedPatterns(watched);
        service.ingest(600, vo);
        ProcessSnapshotResponseVO latest = service.getLatest(600);
        Assertions.assertNotNull(latest);
        Assertions.assertEquals(2, latest.getWatchedPatterns().size());
        Assertions.assertTrue(latest.getWatchedPatterns().get("^java$"));
        Assertions.assertFalse(latest.getWatchedPatterns().get("^missing$"));
    }

    @Test
    void ingest_shouldOverwritePreviousSnapshotForSameClient() {
        service.ingest(700, sampleVo());
        ProcessSnapshotVO vo2 = sampleVo();
        vo2.getTop10ByCpu().get(0).setName("new-java");
        service.ingest(700, vo2);
        ProcessSnapshotResponseVO latest = service.getLatest(700);
        Assertions.assertEquals("new-java", latest.getTop10ByCpu().get(0).getName());
    }

    /**
     * 构造一份典型测试数据。
     *
     * @return 进程快照请求 VO
     */
    private ProcessSnapshotVO sampleVo() {
        ProcessSnapshotVO vo = new ProcessSnapshotVO();
        vo.setTimestamp(1700000000000L);
        ProcessSnapshotVO.ProcessInfoVO a = new ProcessSnapshotVO.ProcessInfoVO();
        a.setName("java");
        a.setPid(1234);
        a.setCpuPercent(0.42);
        a.setMemoryBytes(2L * 1024 * 1024 * 1024);
        ProcessSnapshotVO.ProcessInfoVO b = new ProcessSnapshotVO.ProcessInfoVO();
        b.setName("nginx");
        b.setPid(5678);
        b.setCpuPercent(0.10);
        b.setMemoryBytes(200L * 1024 * 1024);
        vo.setTop10ByCpu(new ArrayList<>(List.of(a, b)));
        vo.setTop10ByMemory(new ArrayList<>(List.of(a, b)));
        Map<String, Boolean> watched = new LinkedHashMap<>();
        watched.put("^java$", true);
        vo.setWatchedPatterns(watched);
        return vo;
    }

    /** 捕获 publishProcessSnapshot 调用的 SseEventBus 桩。 */
    private static class CapturingSseBus implements SseEventBus {
        final List<CapturedPublish> processPublishes = new ArrayList<>();

        @Override
        public void publishClientList() { /* no-op */ }

        @Override
        public void publishRuntime(int clientId, RuntimeDetailVO vo) { /* no-op */ }

        @Override
        public void publishAlertFired(AlertHistoryVO vo) { /* no-op */ }

        @Override
        public void publishSystemdSnapshot(int clientId, SystemdSnapshotResponseVO vo) { /* no-op */ }

        @Override
        public void publishSmartSnapshot(int clientId, SmartSnapshotResponseVO vo) { /* no-op */ }

        @Override
        public void publishProcessSnapshot(int clientId, ProcessSnapshotResponseVO vo) {
            processPublishes.add(new CapturedPublish(clientId, vo));
        }

        @Override
        public void publishGpuSnapshot(int clientId, GpuSnapshotResponseVO vo) { /* no-op */ }
    }

    /** 在 publishProcessSnapshot 中抛异常的 SseEventBus 桩。 */
    private static class ThrowingSseBus implements SseEventBus {
        final AtomicReference<Boolean> called;

        ThrowingSseBus(AtomicReference<Boolean> called) {
            this.called = called;
        }

        @Override
        public void publishClientList() { /* no-op */ }

        @Override
        public void publishRuntime(int clientId, RuntimeDetailVO vo) { /* no-op */ }

        @Override
        public void publishAlertFired(AlertHistoryVO vo) { /* no-op */ }

        @Override
        public void publishSystemdSnapshot(int clientId, SystemdSnapshotResponseVO vo) { /* no-op */ }

        @Override
        public void publishSmartSnapshot(int clientId, SmartSnapshotResponseVO vo) { /* no-op */ }

        @Override
        public void publishProcessSnapshot(int clientId, ProcessSnapshotResponseVO vo) {
            called.set(true);
            throw new RuntimeException("simulated SSE failure");
        }

        @Override
        public void publishGpuSnapshot(int clientId, GpuSnapshotResponseVO vo) { /* no-op */ }
    }

    private record CapturedPublish(int clientId, ProcessSnapshotResponseVO vo) {}
}
