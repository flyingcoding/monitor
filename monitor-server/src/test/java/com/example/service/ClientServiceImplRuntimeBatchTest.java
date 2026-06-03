package com.example.service;

import com.example.config.SseEventBus;
import com.example.entity.dto.Client;
import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.entity.vo.response.AlertHistoryVO;
import com.example.entity.vo.response.GpuSnapshotResponseVO;
import com.example.entity.vo.response.ProcessSnapshotResponseVO;
import com.example.entity.vo.response.RuntimeHistoryVO;
import com.example.entity.vo.response.SmartSnapshotResponseVO;
import com.example.entity.vo.response.SystemdSnapshotResponseVO;
import com.example.service.impl.ClientServiceImpl;
import com.example.tsdb.TimeSeriesAdapter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link ClientServiceImpl} 运行时批量上报路径测试。
 */
class ClientServiceImplRuntimeBatchTest {

    @Test
    void updateRuntimeDetailsShouldBatchTsdbAndKeepPerRuntimeSideEffects() {
        ClientServiceImpl service = new ClientServiceImpl();
        RecordingTimeSeriesAdapter adapter = new RecordingTimeSeriesAdapter();
        RecordingSseEventBus sseEventBus = new RecordingSseEventBus();
        AtomicInteger alertCount = new AtomicInteger(0);
        ReflectionTestUtils.setField(service, "influx", adapter);
        ReflectionTestUtils.setField(service, "sseEventBus", sseEventBus);
        ReflectionTestUtils.setField(service, "alertEvaluator",
                (AlertEvaluator) (clientId, runtime) -> alertCount.incrementAndGet());

        Client client = new Client(42, "host", "token", "cn", "node", new Date(), null);
        List<RuntimeDetailVO> batch = List.of(runtime(1_700_000_000_000L), runtime(1_700_000_010_000L));

        service.updateRuntimeDetails(batch, client);

        Assertions.assertEquals(1, adapter.batchWriteCount, "TSDB 应只执行一次批量写入");
        Assertions.assertEquals(0, adapter.singleWriteCount, "批量路径不应退回逐条 TSDB 写入");
        Assertions.assertEquals(2, adapter.lastBatchSize);
        Assertions.assertEquals(2, sseEventBus.runtimeCount.get(), "runtime SSE 仍保持逐条推送");
        Assertions.assertEquals(2, sseEventBus.clientListCount.get(), "client-list SSE 仍保持逐条刷新");
        Assertions.assertEquals(2, alertCount.get(), "告警评估仍保持逐条执行");
    }

    /**
     * 构造满足 RuntimeDetailVO 基础字段约束的测试样本。
     *
     * @param timestamp 样本时间戳
     * @return 运行时数据
     */
    private RuntimeDetailVO runtime(long timestamp) {
        RuntimeDetailVO vo = new RuntimeDetailVO();
        vo.setTimestamp(timestamp);
        vo.setCpuUsage(0.5);
        vo.setMemoryUsage(1.0);
        vo.setDiskUsage(2.0);
        vo.setNetworkUpload(3.0);
        vo.setNetworkDownload(4.0);
        vo.setDiskRead(5.0);
        vo.setDiskWrite(6.0);
        return vo;
    }

    private static class RecordingTimeSeriesAdapter implements TimeSeriesAdapter {
        private int singleWriteCount;
        private int batchWriteCount;
        private int lastBatchSize;

        @Override
        public void writeRuntime(int clientId, RuntimeDetailVO vo) {
            singleWriteCount++;
        }

        @Override
        public void writeRuntimeBatch(int clientId, List<RuntimeDetailVO> batch) {
            batchWriteCount++;
            lastBatchSize = batch.size();
        }

        @Override
        public void writeOtlpMetric(int clientId, RuntimeDetailVO vo) {
            writeRuntime(clientId, vo);
        }

        @Override
        public RuntimeHistoryVO readRuntimeHistory(int clientId, Instant from, Instant to) {
            return new RuntimeHistoryVO();
        }

        @Override
        public double[] readAvailabilityBuckets(int clientId) {
            return new double[0];
        }
    }

    private static class RecordingSseEventBus implements SseEventBus {
        private final AtomicInteger runtimeCount = new AtomicInteger(0);
        private final AtomicInteger clientListCount = new AtomicInteger(0);

        @Override
        public void publishClientList() {
            clientListCount.incrementAndGet();
        }

        @Override
        public void publishRuntime(int clientId, RuntimeDetailVO vo) {
            runtimeCount.incrementAndGet();
        }

        @Override
        public void publishAlertFired(AlertHistoryVO vo) {
            // not used in this test
        }

        @Override
        public void publishSystemdSnapshot(int clientId, SystemdSnapshotResponseVO vo) {
            // not used in this test
        }

        @Override
        public void publishSmartSnapshot(int clientId, SmartSnapshotResponseVO vo) {
            // not used in this test
        }

        @Override
        public void publishProcessSnapshot(int clientId, ProcessSnapshotResponseVO vo) {
            // not used in this test
        }

        @Override
        public void publishGpuSnapshot(int clientId, GpuSnapshotResponseVO vo) {
            // not used in this test
        }
    }
}
