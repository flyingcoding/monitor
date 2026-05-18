package com.example.service.impl.probe;

import com.example.entity.dto.ProbeTask;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * {@link IcmpProbeExecutor} 单元测试。
 *
 * <p>JDK {@code InetAddress.isReachable} 在容器 / 普通用户场景下通常不能发真正的 ICMP（无 raw socket 权限）；
 * 它退化为 TCP echo (port 7) 探测。因此 "127.0.0.1 可达" 在测试环境也不一定为 true。
 * 这里的测试只验证：(a) 错误输入不抛异常，(b) 返回 {@link ProbeResult}，(c) target 解析忽略端口。
 */
class IcmpProbeExecutorTest {

    private final IcmpProbeExecutor executor = new IcmpProbeExecutor();

    @Test
    void shouldHandleLocalhost() {
        ProbeTask t = task("127.0.0.1", 2);
        ProbeResult result = executor.execute(t, Map.of(), null);
        // 不关心 success 真假，只要返回非 null 即可（结果由 OS 权限决定）
        Assertions.assertNotNull(result);
        Assertions.assertNotNull(result.getLatencyMs());
    }

    @Test
    void shouldRejectEmptyTarget() {
        ProbeTask t = task("", 2);
        ProbeResult result = executor.execute(t, Map.of(), null);
        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals("target 为空", result.getErrorMessage());
    }

    @Test
    void shouldRejectNullTask() {
        ProbeResult result = executor.execute(null, Map.of(), null);
        Assertions.assertFalse(result.isSuccess());
    }

    @Test
    void shouldStripPortFromTarget() {
        ProbeTask t = task("127.0.0.1:8080", 2);
        // 不应因 ":8080" 抛 UnknownHostException
        ProbeResult result = executor.execute(t, Map.of(), null);
        Assertions.assertNotNull(result);
        // 失败原因不应该是 unknown host 之类（因为 host 部分被正确剥离）
        if (!result.isSuccess() && result.getErrorMessage() != null) {
            Assertions.assertFalse(result.getErrorMessage().toLowerCase().contains("unknown"));
        }
    }

    @Test
    void shouldFailForInvalidHostname() {
        ProbeTask t = task("this-host-definitely-does-not-exist-xyz123.invalid", 1);
        ProbeResult result = executor.execute(t, Map.of(), null);
        Assertions.assertFalse(result.isSuccess());
        Assertions.assertNotNull(result.getErrorMessage());
    }

    private ProbeTask task(String target, int timeoutSec) {
        ProbeTask t = new ProbeTask();
        t.setId(1L);
        t.setName("icmp-test");
        t.setType("icmp");
        t.setTarget(target);
        t.setTimeoutSec(timeoutSec);
        t.setIntervalSec(60);
        t.setConsecutiveFailuresThreshold(2);
        t.setEnabled(Boolean.TRUE);
        return t;
    }
}
