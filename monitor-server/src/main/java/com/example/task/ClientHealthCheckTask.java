package com.example.task;

import com.example.entity.dto.Client;
import com.example.entity.dto.ClientSsh;
import com.example.service.ClientService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 对心跳过期客户端执行主动TCP探活，连续失败达到阈值后强制标记离线。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "monitor.health-check.enabled", havingValue = "true")
public class ClientHealthCheckTask {

    @Resource
    private ClientService clientService;

    @Value("${monitor.health-check.stale-threshold-ms:60000}")
    private long staleThresholdMs;

    @Value("${monitor.health-check.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${monitor.health-check.failure-threshold:3}")
    private int failureThreshold;

    private final Map<Integer, Integer> failureCounter = new ConcurrentHashMap<>();

    /**
     * 扫描候选主机并执行SSH端口探测。
     */
    @Scheduled(fixedDelayString = "${monitor.health-check.interval:30000}")
    public void checkClientHealth() {
        List<Client> candidates = clientService.listHealthCheckCandidates(staleThresholdMs);
        if (candidates.isEmpty()) {
            failureCounter.clear();
            return;
        }
        Set<Integer> candidateIds = candidates.stream().map(Client::getId).collect(Collectors.toSet());
        failureCounter.keySet().removeIf(id -> !candidateIds.contains(id));
        for (Client client : candidates) {
            ClientSsh ssh = clientService.findClientSsh(client.getId());
            if (!this.isSshConfigValid(ssh)) {
                continue;
            }
            if (this.checkTcpReachable(ssh.getIp(), ssh.getPort())) {
                failureCounter.remove(client.getId());
            } else {
                int failures = failureCounter.merge(client.getId(), 1, Integer::sum);
                log.warn("客户端 {} 主动探活失败，第{}次", client.getId(), failures);
                if (failures >= failureThreshold) {
                    clientService.forceClientOffline(client.getId());
                    failureCounter.remove(client.getId());
                }
            }
        }
    }

    /**
     * 校验SSH探活参数是否可用。
     *
     * @param ssh SSH配置
     * @return 是否可用
     */
    private boolean isSshConfigValid(ClientSsh ssh) {
        return ssh != null
                && ssh.getIp() != null
                && !ssh.getIp().isBlank()
                && ssh.getPort() != null
                && ssh.getPort() > 0;
    }

    /**
     * 执行TCP端口连通性探测。
     *
     * @param ip 目标IP
     * @param port 目标端口
     * @return 是否连通
     */
    private boolean checkTcpReachable(String ip, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), connectTimeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
