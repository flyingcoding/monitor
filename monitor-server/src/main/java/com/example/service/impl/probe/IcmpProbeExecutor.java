package com.example.service.impl.probe;

import com.example.entity.dto.ProbeTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Map;

/**
 * ICMP / Ping 探测执行器。
 *
 * <p>策略：
 * <ol>
 *   <li>首选 {@link InetAddress#isReachable(int)}：在 Linux 上若 JVM 有 raw socket 权限会发 ICMP，
 *       否则会退化为 TCP 7（echo）端口探测；超时返回 false。</li>
 *   <li>容器环境很少给 JVM raw socket 权限，因此 {@code isReachable} 通常退化为 TCP echo —— 这与
 *       传统 ping 不等价但仍能反映"主机可达"。需要更强可靠性可在 v1.4+ 引入 OS ping 调用。</li>
 * </ol>
 *
 * <p>target 只取 host 部分（如 {@code 8.8.8.8} 或 {@code mail.example.com}），不含端口。
 */
@Slf4j
@Component
public class IcmpProbeExecutor implements ProbeExecutor {

    @Override
    public ProbeResult execute(ProbeTask task,
                               Map<String, String> decryptedHeaders,
                               String decryptedBasicPwd) {
        if (task == null || task.getTarget() == null || task.getTarget().isBlank()) {
            return ProbeResult.builder()
                    .success(false)
                    .errorMessage("target 为空")
                    .build();
        }
        String host = task.getTarget().trim();
        // 即便用户填了 host:port 也只取 host 部分
        int colonIdx = host.indexOf(':');
        if (colonIdx > 0) {
            host = host.substring(0, colonIdx);
        }
        int timeoutMs = (task.getTimeoutSec() == null ? 10 : task.getTimeoutSec()) * 1000;
        long start = System.currentTimeMillis();
        try {
            InetAddress addr = InetAddress.getByName(host);
            boolean reachable = addr.isReachable(timeoutMs);
            int latency = (int) (System.currentTimeMillis() - start);
            if (reachable) {
                return ProbeResult.builder()
                        .success(true)
                        .latencyMs(latency)
                        .build();
            }
            return ProbeResult.builder()
                    .success(false)
                    .latencyMs(latency)
                    .errorMessage("ICMP/echo 不可达")
                    .build();
        } catch (Exception ex) {
            int latency = (int) (System.currentTimeMillis() - start);
            String msg = ex.getMessage();
            if (msg == null) {
                msg = ex.getClass().getSimpleName();
            }
            return ProbeResult.builder()
                    .success(false)
                    .latencyMs(latency)
                    .errorMessage(msg)
                    .build();
        }
    }
}
