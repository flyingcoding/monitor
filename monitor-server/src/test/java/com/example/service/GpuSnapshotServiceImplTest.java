package com.example.service;

import com.example.config.SseEventBus;
import com.example.entity.vo.request.GpuSnapshotVO;
import com.example.entity.vo.request.GpuStatVO;
import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.entity.vo.response.AlertHistoryVO;
import com.example.entity.vo.response.GpuSnapshotResponseVO;
import com.example.entity.vo.response.ProcessSnapshotResponseVO;
import com.example.entity.vo.response.SmartSnapshotResponseVO;
import com.example.entity.vo.response.SystemdSnapshotResponseVO;
import com.example.service.impl.GpuSnapshotServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link GpuSnapshotServiceImpl} 单元测试。
 * <p>
 * 沿用项目惯例：手工构造 stub 替代 Mockito（与 SystemdSnapshotServiceImplTest 一致）。
 * 覆盖：
 * <ul>
 *   <li>ingest 写缓存 + 触发 SSE 推送；</li>
 *   <li>getLatest 命中缓存；</li>
 *   <li>getLatest 未命中（缓存为空）返回 null；</li>
 *   <li>ingest null clientId / null VO 应静默忽略；</li>
 *   <li>SSE 推送异常被吞掉不影响缓存写入；</li>
 *   <li>请求 VO 字段透传到响应 VO；</li>
 *   <li>后续 ingest 覆盖旧缓存。</li>
 * </ul>
 */
class GpuSnapshotServiceImplTest {

    private GpuSnapshotServiceImpl service;
    private final AtomicInteger publishCount = new AtomicInteger();
    private final AtomicReference<GpuSnapshotResponseVO> lastPublished = new AtomicReference<>();
    private final AtomicReference<Integer> lastClientId = new AtomicReference<>();
    private volatile boolean publishThrows = false;

    @BeforeEach
    void setUp() {
        publishCount.set(0);
        lastPublished.set(null);
        lastClientId.set(null);
        publishThrows = false;

        service = new GpuSnapshotServiceImpl();
        SseEventBus sseEventBus = new SseEventBus() {
            @Override
            public void publishClientList() {}

            @Override
            public void publishRuntime(int clientId, RuntimeDetailVO vo) {}

            @Override
            public void publishAlertFired(AlertHistoryVO vo) {}

            @Override
            public void publishSystemdSnapshot(int clientId, SystemdSnapshotResponseVO vo) {}

            @Override
            public void publishSmartSnapshot(int clientId, SmartSnapshotResponseVO vo) {}

            @Override
            public void publishProcessSnapshot(int clientId, ProcessSnapshotResponseVO vo) {}

            @Override
            public void publishGpuSnapshot(int clientId, GpuSnapshotResponseVO vo) {
                if (publishThrows) {
                    throw new RuntimeException("sse boom");
                }
                publishCount.incrementAndGet();
                lastClientId.set(clientId);
                lastPublished.set(vo);
            }
        };
        ReflectionTestUtils.setField(service, "sseEventBus", sseEventBus);
    }

    /**
     * ingest 应写入缓存并触发一次 SSE 推送。
     */
    @Test
    void ingest_shouldCacheAndPublishSse() {
        GpuSnapshotVO vo = sampleVo();
        service.ingest(100, vo);

        Assertions.assertEquals(1, publishCount.get(), "应触发一次 SSE 推送");
        Assertions.assertEquals(Integer.valueOf(100), lastClientId.get());

        GpuSnapshotResponseVO cached = service.getLatest(100);
        Assertions.assertNotNull(cached);
        Assertions.assertEquals(Integer.valueOf(100), cached.getClientId());
        Assertions.assertNotNull(cached.getUpdatedAt());
        Assertions.assertEquals(2, cached.getGpus().size());
        Assertions.assertEquals("NVIDIA GeForce RTX 4090", cached.getGpus().get(0).getName());
        Assertions.assertEquals(75.0, cached.getGpus().get(1).getTemperatureCelsius());
    }

    /**
     * 未上报的客户端应返回 null。
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
        service.ingest(null, sampleVo());
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

        service.ingest(200, sampleVo());

        GpuSnapshotResponseVO cached = service.getLatest(200);
        Assertions.assertNotNull(cached, "SSE 推送失败时缓存仍应被写入");
        Assertions.assertEquals(Integer.valueOf(200), cached.getClientId());
    }

    /**
     * 空 gpus 列表（无 NVIDIA GPU / 命令失败时客户端上报空）应能正常入缓存。
     */
    @Test
    void ingest_shouldHandleEmptyGpus() {
        GpuSnapshotVO vo = new GpuSnapshotVO();
        vo.setGpus(new ArrayList<>());

        service.ingest(300, vo);

        GpuSnapshotResponseVO cached = service.getLatest(300);
        Assertions.assertNotNull(cached);
        Assertions.assertTrue(cached.getGpus().isEmpty());
    }

    /**
     * gpus == null 应能正常入缓存（保护性测试，防止 NPE）。
     */
    @Test
    void ingest_shouldHandleNullGpusField() {
        GpuSnapshotVO vo = new GpuSnapshotVO();
        vo.setGpus(null);

        service.ingest(400, vo);

        GpuSnapshotResponseVO cached = service.getLatest(400);
        Assertions.assertNotNull(cached);
        Assertions.assertTrue(cached.getGpus().isEmpty());
    }

    /**
     * 后续 ingest 应覆盖前一次的缓存（保留最新快照）。
     */
    @Test
    void ingest_shouldOverwriteCacheOnNewSnapshot() {
        GpuSnapshotVO firstVo = new GpuSnapshotVO();
        firstVo.setGpus(Collections.singletonList(buildStat(0, "NVIDIA GeForce RTX 4090", 60.0)));
        service.ingest(600, firstVo);

        GpuSnapshotResponseVO first = service.getLatest(600);
        Assertions.assertEquals(1, first.getGpus().size());

        GpuSnapshotVO secondVo = new GpuSnapshotVO();
        secondVo.setGpus(Arrays.asList(
                buildStat(0, "NVIDIA GeForce RTX 4090", 75.0),
                buildStat(1, "NVIDIA GeForce RTX 3090", 70.0)));
        service.ingest(600, secondVo);

        GpuSnapshotResponseVO second = service.getLatest(600);
        Assertions.assertEquals(2, second.getGpus().size(), "新快照应覆盖旧快照");
        Assertions.assertEquals(75.0, second.getGpus().get(0).getTemperatureCelsius());
    }

    /**
     * 验证 ingest 后 GPU 全部字段被透传到响应。
     */
    @Test
    void ingest_shouldMapAllFields() {
        GpuStatVO stat = new GpuStatVO();
        stat.setIndex(2);
        stat.setName("NVIDIA Tesla V100");
        stat.setUtilizationPercent(82.5);
        stat.setMemoryUsedMb(28000.0);
        stat.setMemoryTotalMb(32768.0);
        stat.setTemperatureCelsius(78.0);
        stat.setPowerDrawWatts(295.5);

        GpuSnapshotVO vo = new GpuSnapshotVO();
        vo.setGpus(Collections.singletonList(stat));

        service.ingest(700, vo);

        GpuSnapshotResponseVO cached = service.getLatest(700);
        Assertions.assertEquals(1, cached.getGpus().size());
        GpuStatVO mapped = cached.getGpus().get(0);
        Assertions.assertEquals(2, mapped.getIndex());
        Assertions.assertEquals("NVIDIA Tesla V100", mapped.getName());
        Assertions.assertEquals(82.5, mapped.getUtilizationPercent());
        Assertions.assertEquals(28000.0, mapped.getMemoryUsedMb());
        Assertions.assertEquals(32768.0, mapped.getMemoryTotalMb());
        Assertions.assertEquals(78.0, mapped.getTemperatureCelsius());
        Assertions.assertEquals(295.5, mapped.getPowerDrawWatts());
    }

    /**
     * gpus 列表中的 null 元素应被过滤掉，不进入缓存。
     */
    @Test
    void ingest_shouldFilterNullEntries() {
        GpuSnapshotVO vo = new GpuSnapshotVO();
        List<GpuStatVO> gpus = new ArrayList<>();
        gpus.add(buildStat(0, "NVIDIA GeForce RTX 4090", 65.0));
        gpus.add(null);
        gpus.add(buildStat(1, "NVIDIA GeForce RTX 3090", 70.0));
        vo.setGpus(gpus);

        service.ingest(800, vo);

        GpuSnapshotResponseVO cached = service.getLatest(800);
        Assertions.assertEquals(2, cached.getGpus().size(), "null 条目应被过滤");
    }

    /**
     * 构造单 GPU 数据。
     *
     * @param index GPU index
     * @param name GPU 名
     * @param temp 温度
     * @return GpuStatVO
     */
    private GpuStatVO buildStat(int index, String name, double temp) {
        GpuStatVO stat = new GpuStatVO();
        stat.setIndex(index);
        stat.setName(name);
        stat.setUtilizationPercent(50.0);
        stat.setMemoryUsedMb(4096.0);
        stat.setMemoryTotalMb(24576.0);
        stat.setTemperatureCelsius(temp);
        stat.setPowerDrawWatts(200.0);
        return stat;
    }

    /**
     * 构造 2 个 GPU 的 sample VO。
     *
     * @return GpuSnapshotVO
     */
    private GpuSnapshotVO sampleVo() {
        GpuSnapshotVO vo = new GpuSnapshotVO();
        vo.setGpus(Arrays.asList(
                buildStat(0, "NVIDIA GeForce RTX 4090", 60.0),
                buildStat(1, "NVIDIA GeForce RTX 3090", 75.0)));
        return vo;
    }
}
