package com.example.service;

import com.example.config.SseEventBus;
import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.entity.vo.request.SmartSnapshotVO;
import com.example.entity.vo.request.SmartStatVO;
import com.example.entity.vo.response.AlertHistoryVO;
import com.example.entity.vo.response.GpuSnapshotResponseVO;
import com.example.entity.vo.response.ProcessSnapshotResponseVO;
import com.example.entity.vo.response.SmartSnapshotResponseVO;
import com.example.entity.vo.response.SystemdSnapshotResponseVO;
import com.example.service.impl.SmartSnapshotServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link SmartSnapshotServiceImpl} 单元测试。
 * <p>
 * 沿用项目惯例：手写 stub 替代 Mockito。覆盖：
 * <ul>
 *   <li>ingest 写缓存 + 触发 SSE 推送；</li>
 *   <li>SSE 推送异常被吞掉，不影响缓存；</li>
 *   <li>getLatest 命中缓存；</li>
 *   <li>null clientId / null vo 安全返回；</li>
 *   <li>空 disks 列表合法处理。</li>
 * </ul>
 */
class SmartSnapshotServiceImplTest {

    private SmartSnapshotServiceImpl service;
    private final AtomicReference<SmartSnapshotResponseVO> lastPublished = new AtomicReference<>();
    private final AtomicReference<Integer> lastPublishedClientId = new AtomicReference<>();
    private volatile boolean publishShouldThrow = false;

    @BeforeEach
    void setUp() {
        lastPublished.set(null);
        lastPublishedClientId.set(null);
        publishShouldThrow = false;
        service = new SmartSnapshotServiceImpl();
        SseEventBus bus = new RecordingSseEventBus();
        ReflectionTestUtils.setField(service, "sseEventBus", bus);
    }

    /**
     * ingest 把 vo 转为响应 VO 入缓存，并向 SSE 总线推送一次事件。
     */
    @Test
    void ingestShouldWriteCacheAndPublishSse() {
        SmartSnapshotVO vo = new SmartSnapshotVO();
        SmartStatVO disk = new SmartStatVO();
        disk.setDevice("/dev/sda");
        disk.setModelName("Samsung 850");
        disk.setNvme(false);
        disk.setReallocatedSector(0L);
        disk.setCurrentPending(0L);
        disk.setOfflineUncorrectable(0L);
        disk.setTemperatureCelsius(35);
        disk.setCritical(false);
        vo.setDisks(List.of(disk));

        service.ingest(42, vo);

        SmartSnapshotResponseVO cached = service.getLatest(42);
        Assertions.assertNotNull(cached);
        Assertions.assertEquals(42, cached.getClientId());
        Assertions.assertNotNull(cached.getUpdatedAt());
        Assertions.assertEquals(1, cached.getDisks().size());
        SmartSnapshotResponseVO.SmartStatResponseVO mapped = cached.getDisks().get(0);
        Assertions.assertEquals("/dev/sda", mapped.getDevice());
        Assertions.assertEquals("Samsung 850", mapped.getModelName());
        Assertions.assertFalse(mapped.isNvme());
        Assertions.assertEquals(35, mapped.getTemperatureCelsius());
        Assertions.assertFalse(mapped.isCritical());

        Assertions.assertEquals(42, lastPublishedClientId.get());
        Assertions.assertSame(cached, lastPublished.get());
    }

    /**
     * SSE 推送抛异常时不应影响缓存写入。
     */
    @Test
    void sseFailureShouldNotBreakCache() {
        publishShouldThrow = true;
        SmartSnapshotVO vo = new SmartSnapshotVO();
        vo.setDisks(List.of());

        service.ingest(7, vo);

        SmartSnapshotResponseVO cached = service.getLatest(7);
        Assertions.assertNotNull(cached, "SSE 异常不应阻塞缓存写入");
        Assertions.assertEquals(0, cached.getDisks().size());
    }

    /**
     * getLatest 未命中应返回 null（前端 tab 显示"暂无数据"）。
     */
    @Test
    void getLatestShouldReturnNullWhenMissing() {
        Assertions.assertNull(service.getLatest(999));
    }

    /**
     * null clientId 或 null vo 应安全返回，不写缓存、不推送。
     */
    @Test
    void nullArgumentsShouldBeSafe() {
        service.ingest(null, new SmartSnapshotVO());
        service.ingest(1, null);
        Assertions.assertNull(service.getLatest(1));
        Assertions.assertNull(service.getLatest(null));
        Assertions.assertNull(lastPublishedClientId.get(),
                "null 输入不应触发 SSE 推送");
    }

    /**
     * NVMe critical 磁盘（media_errors&gt;0）应原样保留 critical=true。
     */
    @Test
    void nvmeCriticalShouldPreserveFlag() {
        SmartSnapshotVO vo = new SmartSnapshotVO();
        SmartStatVO disk = new SmartStatVO();
        disk.setDevice("/dev/nvme1n1");
        disk.setNvme(true);
        disk.setMediaErrors(17L);
        disk.setTemperatureCelsius(75);
        disk.setCritical(true);
        vo.setDisks(List.of(disk));

        service.ingest(101, vo);

        SmartSnapshotResponseVO cached = service.getLatest(101);
        Assertions.assertNotNull(cached);
        SmartSnapshotResponseVO.SmartStatResponseVO mapped = cached.getDisks().get(0);
        Assertions.assertTrue(mapped.isNvme());
        Assertions.assertTrue(mapped.isCritical());
        Assertions.assertEquals(17L, mapped.getMediaErrors());
    }

    /**
     * null disks 列表合法处理为空列表，避免 NPE。
     */
    @Test
    void nullDisksShouldBeMappedToEmptyList() {
        SmartSnapshotVO vo = new SmartSnapshotVO();
        vo.setDisks(null);

        service.ingest(11, vo);

        SmartSnapshotResponseVO cached = service.getLatest(11);
        Assertions.assertNotNull(cached);
        Assertions.assertEquals(0, cached.getDisks().size());
    }

    /**
     * 简化 SSE 总线 stub，仅记录 SMART 推送参数。
     */
    private class RecordingSseEventBus implements SseEventBus {

        @Override
        public void publishClientList() {
        }

        @Override
        public void publishRuntime(int clientId, RuntimeDetailVO vo) {
        }

        @Override
        public void publishAlertFired(AlertHistoryVO vo) {
        }

        @Override
        public void publishSystemdSnapshot(int clientId, SystemdSnapshotResponseVO vo) {
        }

        @Override
        public void publishSmartSnapshot(int clientId, SmartSnapshotResponseVO vo) {
            if (publishShouldThrow) {
                throw new RuntimeException("simulated SSE failure");
            }
            lastPublishedClientId.set(clientId);
            lastPublished.set(vo);
        }

        @Override
        public void publishProcessSnapshot(int clientId, ProcessSnapshotResponseVO vo) {
        }

        @Override
        public void publishGpuSnapshot(int clientId, GpuSnapshotResponseVO vo) {
        }
    }
}
