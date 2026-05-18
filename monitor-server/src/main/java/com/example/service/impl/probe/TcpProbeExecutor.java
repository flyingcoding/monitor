package com.example.service.impl.probe;

import com.example.entity.dto.ProbeTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;

/**
 * TCP 端口探测执行器。
 *
 * <p>{@link ProbeTask#getTarget()} 接受两种格式：
 * <ul>
 *   <li>{@code host:port}（推荐）</li>
 *   <li>{@code host} —— 此时认为是配置错误，返回失败"未指定端口"</li>
 * </ul>
 *
 * <p>connect 成功即视为 success；connect 超时或被拒视为失败。
 */
@Slf4j
@Component
public class TcpProbeExecutor implements ProbeExecutor {

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
        String[] parts = task.getTarget().split(":", 2);
        if (parts.length != 2) {
            return ProbeResult.builder()
                    .success(false)
                    .errorMessage("TCP target 需为 host:port 格式")
                    .build();
        }
        String host = parts[0].trim();
        int port;
        try {
            port = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException ex) {
            return ProbeResult.builder()
                    .success(false)
                    .errorMessage("TCP port 不是合法整数")
                    .build();
        }
        if (port < 1 || port > 65535) {
            return ProbeResult.builder()
                    .success(false)
                    .errorMessage("TCP port 需在 1~65535 范围")
                    .build();
        }
        int timeoutMs = (task.getTimeoutSec() == null ? 10 : task.getTimeoutSec()) * 1000;
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            int latency = (int) (System.currentTimeMillis() - start);
            return ProbeResult.builder()
                    .success(true)
                    .latencyMs(latency)
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
