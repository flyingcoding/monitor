package com.example.service.impl.probe;

import com.example.entity.dto.ProbeTask;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.Map;

/**
 * {@link TcpProbeExecutor} 单元测试。使用真实 {@link ServerSocket} 验证 connect 成功 / 失败。
 */
class TcpProbeExecutorTest {

    private final TcpProbeExecutor executor = new TcpProbeExecutor();

    @Test
    void shouldSucceedConnectingToLocalListener() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            ProbeTask t = task("127.0.0.1:" + port, 2);
            ProbeResult result = executor.execute(t, Map.of(), null);
            Assertions.assertTrue(result.isSuccess(), "本地 listener 应连接成功");
            Assertions.assertNotNull(result.getLatencyMs());
        }
    }

    @Test
    void shouldFailOnUnreachablePort() {
        // 选一个极不可能被监听的端口 + RFC5737 测试 IP
        ProbeTask t = task("127.0.0.1:1", 1);
        ProbeResult result = executor.execute(t, Map.of(), null);
        Assertions.assertFalse(result.isSuccess(), "未监听端口应连接失败");
        Assertions.assertNotNull(result.getErrorMessage());
    }

    @Test
    void shouldRejectMalformedTarget() {
        ProbeTask t = task("invalid-target-no-port", 2);
        ProbeResult result = executor.execute(t, Map.of(), null);
        Assertions.assertFalse(result.isSuccess());
        Assertions.assertTrue(result.getErrorMessage().contains("host:port"));
    }

    @Test
    void shouldRejectOutOfRangePort() {
        ProbeTask t = task("127.0.0.1:99999", 2);
        ProbeResult result = executor.execute(t, Map.of(), null);
        Assertions.assertFalse(result.isSuccess());
        Assertions.assertTrue(result.getErrorMessage().contains("port"));
    }

    @Test
    void shouldRejectNonNumericPort() {
        ProbeTask t = task("127.0.0.1:abc", 2);
        ProbeResult result = executor.execute(t, Map.of(), null);
        Assertions.assertFalse(result.isSuccess());
        Assertions.assertTrue(result.getErrorMessage().contains("port"));
    }

    @Test
    void shouldRejectNullTask() {
        ProbeResult result = executor.execute(null, Map.of(), null);
        Assertions.assertFalse(result.isSuccess());
    }

    private ProbeTask task(String target, int timeoutSec) {
        ProbeTask t = new ProbeTask();
        t.setId(1L);
        t.setName("tcp-test");
        t.setType("tcp");
        t.setTarget(target);
        t.setTimeoutSec(timeoutSec);
        t.setIntervalSec(60);
        t.setConsecutiveFailuresThreshold(2);
        t.setEnabled(Boolean.TRUE);
        return t;
    }
}
