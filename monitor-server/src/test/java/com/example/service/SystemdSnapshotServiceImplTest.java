package com.example.service;

import com.example.config.SseEventBus;
import com.example.entity.vo.request.SystemdSnapshotVO;
import com.example.entity.vo.request.SystemdUnitStatVO;
import com.example.entity.vo.response.AlertHistoryVO;
import com.example.entity.vo.response.GpuSnapshotResponseVO;
import com.example.entity.vo.response.ProcessSnapshotResponseVO;
import com.example.entity.vo.response.SmartSnapshotResponseVO;
import com.example.entity.vo.response.SystemdSnapshotResponseVO;
import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.service.impl.SystemdSnapshotServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link SystemdSnapshotServiceImpl} 单元测试。
 * <p>
 * 沿用项目惯例：手工构造 stub 替代 Mockito（与 ClientControllerTest 一致）。
 * 覆盖：
 * <ul>
 *   <li>ingest 写缓存 + 触发 SSE 推送；</li>
 *   <li>getLatest 命中缓存；</li>
 *   <li>getLatest 未命中（缓存为空）返回 null；</li>
 *   <li>ingest null clientId / null VO 应静默忽略；</li>
 *   <li>空 units 列表 → 缓存中保存空列表；</li>
 *   <li>SSE 推送异常被吞掉不影响缓存写入；</li>
 *   <li>请求 VO 转响应 VO 字段一致；</li>
 *   <li>getLatest null clientId 返回 null。</li>
 * </ul>
 */
class SystemdSnapshotServiceImplTest {

    private SystemdSnapshotServiceImpl service;
    private final AtomicInteger publishCount = new AtomicInteger();
    private final AtomicReference<SystemdSnapshotResponseVO> lastPublished = new AtomicReference<>();
    private final AtomicReference<Integer> lastClientId = new AtomicReference<>();
    private volatile boolean publishThrows = false;

    @BeforeEach
    void setUp() {
        publishCount.set(0);
        lastPublished.set(null);
        lastClientId.set(null);
        publishThrows = false;

        service = new SystemdSnapshotServiceImpl();
        SseEventBus sseEventBus = new SseEventBus() {
            @Override
            public void publishClientList() {}

            @Override
            public void publishRuntime(int clientId, RuntimeDetailVO vo) {}

            @Override
            public void publishAlertFired(AlertHistoryVO vo) {}

            @Override
            public void publishSystemdSnapshot(int clientId, SystemdSnapshotResponseVO vo) {
                if (publishThrows) {
                    throw new RuntimeException("sse boom");
                }
                publishCount.incrementAndGet();
                lastClientId.set(clientId);
                lastPublished.set(vo);
            }

            @Override
            public void publishSmartSnapshot(int clientId, SmartSnapshotResponseVO vo) {}

            @Override
            public void publishProcessSnapshot(int clientId, ProcessSnapshotResponseVO vo) {}

            @Override
            public void publishGpuSnapshot(int clientId, GpuSnapshotResponseVO vo) {}
        };
        ReflectionTestUtils.setField(service, "sseEventBus", sseEventBus);
    }

    /**
     * ingest 应写入缓存并触发一次 SSE 推送。
     */
    @Test
    void ingest_shouldCacheAndPublishSse() {
        SystemdSnapshotVO vo = sampleVo(true);
        service.ingest(100, vo);

        Assertions.assertEquals(1, publishCount.get(), "应触发一次 SSE 推送");
        Assertions.assertEquals(Integer.valueOf(100), lastClientId.get());

        SystemdSnapshotResponseVO cached = service.getLatest(100);
        Assertions.assertNotNull(cached);
        Assertions.assertEquals(Integer.valueOf(100), cached.getClientId());
        Assertions.assertNotNull(cached.getUpdatedAt());
        Assertions.assertEquals(1, cached.getUnits().size());
        Assertions.assertEquals("nginx.service", cached.getUnits().get(0).getName());
        Assertions.assertTrue(cached.getUnits().get(0).isHealthy());
    }

    /**
     * 未上报客户端应返回 null。
     */
    @Test
    void getLatest_shouldReturnNullWhenNotCached() {
        Assertions.assertNull(service.getLatest(999));
    }

    /**
     * null clientId 应返回 null，不抛异常。
     */
    @Test
    void getLatest_shouldReturnNullForNullClientId() {
        Assertions.assertNull(service.getLatest(null));
    }

    /**
     * ingest 接收 null 参数应静默忽略，不抛异常也不推送 SSE。
     */
    @Test
    void ingest_shouldSilentlyIgnoreNulls() {
        service.ingest(null, sampleVo(true));
        service.ingest(100, null);

        Assertions.assertEquals(0, publishCount.get(), "null 参数不应触发 SSE 推送");
        Assertions.assertNull(service.getLatest(100));
    }

    /**
     * SSE 推送异常时缓存仍应被写入，调用方不受影响。
     */
    @Test
    void ingest_shouldStillCacheWhenSseFails() {
        publishThrows = true;

        service.ingest(200, sampleVo(true));

        // 缓存应已写入
        SystemdSnapshotResponseVO cached = service.getLatest(200);
        Assertions.assertNotNull(cached, "SSE 推送失败时缓存仍应被写入");
        Assertions.assertEquals(Integer.valueOf(200), cached.getClientId());
    }

    /**
     * 空 units 列表（采集模块禁用或所有 unit 解析失败）应能正常入缓存。
     */
    @Test
    void ingest_shouldHandleEmptyUnits() {
        SystemdSnapshotVO vo = new SystemdSnapshotVO();
        vo.setUnits(new ArrayList<>());

        service.ingest(300, vo);

        SystemdSnapshotResponseVO cached = service.getLatest(300);
        Assertions.assertNotNull(cached);
        Assertions.assertTrue(cached.getUnits().isEmpty());
    }

    /**
     * units == null 应能正常入缓存（保护性测试，防止 NPE）。
     */
    @Test
    void ingest_shouldHandleNullUnitsField() {
        SystemdSnapshotVO vo = new SystemdSnapshotVO();
        vo.setUnits(null);

        service.ingest(400, vo);

        SystemdSnapshotResponseVO cached = service.getLatest(400);
        Assertions.assertNotNull(cached);
        Assertions.assertTrue(cached.getUnits().isEmpty());
    }

    /**
     * 验证 ingest 后多个 unit 全部字段被正确映射。
     */
    @Test
    void ingest_shouldMapAllFields() {
        SystemdSnapshotVO vo = new SystemdSnapshotVO();
        List<SystemdUnitStatVO> units = new ArrayList<>();

        SystemdUnitStatVO unit1 = new SystemdUnitStatVO();
        unit1.setName("nginx.service");
        unit1.setLoadState("loaded");
        unit1.setActiveState("active");
        unit1.setSubState("running");
        unit1.setDescription("Web server");
        unit1.setHealthy(true);
        units.add(unit1);

        SystemdUnitStatVO unit2 = new SystemdUnitStatVO();
        unit2.setName("mysql.service");
        unit2.setLoadState("loaded");
        unit2.setActiveState("failed");
        unit2.setSubState("failed");
        unit2.setDescription("MySQL DB");
        unit2.setHealthy(false);
        units.add(unit2);

        vo.setUnits(units);

        service.ingest(500, vo);

        SystemdSnapshotResponseVO cached = service.getLatest(500);
        Assertions.assertEquals(2, cached.getUnits().size());

        SystemdSnapshotResponseVO.SystemdUnitStatResponseVO r1 = cached.getUnits().get(0);
        Assertions.assertEquals("nginx.service", r1.getName());
        Assertions.assertEquals("loaded", r1.getLoadState());
        Assertions.assertEquals("active", r1.getActiveState());
        Assertions.assertEquals("running", r1.getSubState());
        Assertions.assertEquals("Web server", r1.getDescription());
        Assertions.assertTrue(r1.isHealthy());

        SystemdSnapshotResponseVO.SystemdUnitStatResponseVO r2 = cached.getUnits().get(1);
        Assertions.assertEquals("mysql.service", r2.getName());
        Assertions.assertFalse(r2.isHealthy());
    }

    /**
     * 后续 ingest 应覆盖前一次的缓存（保留最新快照）。
     */
    @Test
    void ingest_shouldOverwriteCacheOnNewSnapshot() {
        SystemdSnapshotVO firstVo = new SystemdSnapshotVO();
        firstVo.setUnits(Collections.singletonList(buildUnit("nginx.service", true)));
        service.ingest(600, firstVo);
        SystemdSnapshotResponseVO first = service.getLatest(600);
        int firstSize = first.getUnits().size();

        SystemdSnapshotVO secondVo = new SystemdSnapshotVO();
        secondVo.setUnits(List.of(
                buildUnit("nginx.service", true),
                buildUnit("docker.service", false)));
        service.ingest(600, secondVo);

        SystemdSnapshotResponseVO second = service.getLatest(600);
        Assertions.assertEquals(2, second.getUnits().size(),
                "新快照应覆盖旧快照，原 size " + firstSize + " -> 2");
    }

    private SystemdUnitStatVO buildUnit(String name, boolean healthy) {
        SystemdUnitStatVO u = new SystemdUnitStatVO();
        u.setName(name);
        u.setLoadState("loaded");
        u.setActiveState(healthy ? "active" : "inactive");
        u.setSubState(healthy ? "running" : "dead");
        u.setDescription("desc " + name);
        u.setHealthy(healthy);
        return u;
    }

    /**
     * 构造一个 1 unit 的 sample VO。
     */
    private SystemdSnapshotVO sampleVo(boolean healthy) {
        SystemdSnapshotVO vo = new SystemdSnapshotVO();
        vo.setUnits(Collections.singletonList(buildUnit("nginx.service", healthy)));
        return vo;
    }
}
