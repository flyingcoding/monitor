package com.example.service.impl;

import com.example.entity.alert.AlertEvent;
import com.example.entity.alert.AlertLevel;
import com.example.entity.dto.ProbeHistory;
import com.example.entity.dto.ProbeTask;
import com.example.service.impl.probe.HttpProbeExecutor;
import com.example.service.impl.probe.IcmpProbeExecutor;
import com.example.service.impl.probe.ProbeExecutor;
import com.example.service.impl.probe.ProbeResult;
import com.example.service.impl.probe.TcpProbeExecutor;
import com.example.utils.Const;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 服务探测调度器。
 *
 * <p>固定 5 秒 tick：每次 tick 从 DB 加载启用的 {@link ProbeTask}，逐一判断是否到了下次执行时间。
 * 每个任务的实际执行被 dispatch 到一个虚拟线程池，避免互相阻塞。
 *
 * <p>失败计数策略：
 * <ul>
 *   <li>探测失败 → 计数器 +1；连续失败 ≥ {@code consecutive_failures_threshold} 时投递 AlertEvent
 *       并将计数器清零（避免每次都重投）；</li>
 *   <li>探测成功 → 计数器清零；</li>
 *   <li>SSL 即将过期 → 独立分支：单次探测即触发"即将过期"告警，与连续失败计数解耦，
 *       并使用进程内 set 防止同一任务每次 tick 重复投递。</li>
 * </ul>
 *
 * <p>这里使用 {@code @Scheduled(fixedDelay=5000)}，由 Spring 默认调度线程驱动，不抢占
 * {@code alertTaskExecutor} 资源；任务执行则统一在自有虚拟线程池处理。
 */
@Slf4j
@Component
public class ProbeScheduler {

    /** 调度 tick 周期（毫秒）。值过小会增加 DB 加载负担；过大会让 interval 精度下降。 */
    private static final long TICK_INTERVAL_MS = 5000L;

    @Resource
    private ProbeServiceImpl probeService;

    @Resource
    private HttpProbeExecutor httpProbeExecutor;

    @Resource
    private TcpProbeExecutor tcpProbeExecutor;

    @Resource
    private IcmpProbeExecutor icmpProbeExecutor;

    @Resource
    @Qualifier("notificationRabbitTemplate")
    private AmqpTemplate rabbitTemplate;

    /** 每个任务 ID 的连续失败次数。 */
    private final Map<Long, AtomicInteger> failureCounters = new ConcurrentHashMap<>();

    /** 每个任务 ID 的"上次执行时间"，用于按 interval_sec 控制调度节奏。 */
    private final Map<Long, Long> lastRunAt = new ConcurrentHashMap<>();

    /** 当前 SSL 已告警的任务集合，避免单次过期窗口内每 tick 都投递。 */
    private final Map<Long, Boolean> sslAlertedTasks = new ConcurrentHashMap<>();

    /** 探测任务执行线程池：虚拟线程 per task，避免 HTTP 慢任务影响其他任务。 */
    private ExecutorService taskExecutor;

    /**
     * 启动时初始化虚拟线程池。
     */
    @PostConstruct
    public void init() {
        this.taskExecutor = Executors.newVirtualThreadPerTaskExecutor();
        log.info("ProbeScheduler 启动，tick={}ms", TICK_INTERVAL_MS);
    }

    /**
     * 关闭时释放线程池。
     */
    @PreDestroy
    public void destroy() {
        if (taskExecutor != null) {
            taskExecutor.shutdownNow();
            try {
                taskExecutor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 主调度 tick：每 5 秒触发。
     */
    @Scheduled(fixedDelay = TICK_INTERVAL_MS, initialDelay = TICK_INTERVAL_MS)
    public void tick() {
        try {
            List<ProbeTask> tasks = probeService.listEnabledTasks();
            long now = System.currentTimeMillis();
            // 清理 sslAlertedTasks 中已不存在 / 已禁用的任务
            sslAlertedTasks.keySet().removeIf(id -> tasks.stream().noneMatch(t -> id.equals(t.getId())));
            failureCounters.keySet().removeIf(id -> tasks.stream().noneMatch(t -> id.equals(t.getId())));
            lastRunAt.keySet().removeIf(id -> tasks.stream().noneMatch(t -> id.equals(t.getId())));

            for (ProbeTask task : tasks) {
                if (!Boolean.TRUE.equals(task.getEnabled())) {
                    continue;
                }
                long intervalMs = (task.getIntervalSec() == null ? 60 : task.getIntervalSec()) * 1000L;
                Long lastRun = lastRunAt.get(task.getId());
                if (lastRun != null && now - lastRun < intervalMs) {
                    continue;
                }
                lastRunAt.put(task.getId(), now);
                taskExecutor.submit(() -> runSingle(task));
            }
        } catch (Exception e) {
            log.warn("ProbeScheduler tick 异常：{}", e.getMessage(), e);
        }
    }

    /**
     * 执行单个任务的完整生命周期：选 executor → 执行 → 写历史 → 失败计数 / 告警。
     *
     * @param task 探测任务
     */
    public void runSingle(ProbeTask task) {
        ProbeExecutor executor = selectExecutor(task);
        if (executor == null) {
            log.warn("探测任务 id={} type={} 未找到 executor", task.getId(), task.getType());
            return;
        }
        Map<String, String> headers = probeService.resolveHeaders(task);
        String basicPwd = probeService.resolveBasicAuthPassword(task);
        ProbeResult result;
        try {
            result = executor.execute(task, headers, basicPwd);
        } catch (Exception e) {
            result = ProbeResult.builder()
                    .success(false)
                    .errorMessage("executor 异常: " + e.getMessage())
                    .build();
        }
        if (result == null) {
            result = ProbeResult.builder().success(false).errorMessage("executor 返回 null").build();
        }
        saveHistory(task, result);
        evaluateAndAlert(task, result);
    }

    /**
     * 选择对应类型的 executor。
     */
    private ProbeExecutor selectExecutor(ProbeTask task) {
        if (task == null || task.getType() == null) {
            return null;
        }
        return switch (task.getType().toLowerCase()) {
            case "http" -> httpProbeExecutor;
            case "tcp" -> tcpProbeExecutor;
            case "icmp" -> icmpProbeExecutor;
            default -> null;
        };
    }

    /**
     * 写入 probe_history 一行；失败仅记录日志不抛出。
     */
    private void saveHistory(ProbeTask task, ProbeResult result) {
        try {
            ProbeHistory history = new ProbeHistory();
            history.setTaskId(task.getId());
            history.setExecutedAt(new Date());
            history.setSuccess(result.isSuccess());
            history.setLatencyMs(result.getLatencyMs());
            history.setStatusCode(result.getStatusCode());
            history.setSslDaysRemaining(result.getSslDaysRemaining());
            String msg = result.getErrorMessage();
            if (msg != null && msg.length() > 1024) {
                msg = msg.substring(0, 1024);
            }
            history.setErrorMessage(msg);
            probeService.saveHistory(history);
        } catch (Exception e) {
            log.warn("写入 probe_history 失败 taskId={} reason={}", task.getId(), e.getMessage());
        }
    }

    /**
     * 维护失败计数与告警投递。
     *
     * <p>SSL 即将过期作为独立分支：单次探测就可触发，与连续失败计数互不影响。
     * 同一任务 SSL 已告警过的不再重复投递，直到 sslDaysRemaining 恢复到阈值以上才重置。
     */
    public void evaluateAndAlert(ProbeTask task, ProbeResult result) {
        AtomicInteger counter = failureCounters.computeIfAbsent(task.getId(), k -> new AtomicInteger(0));
        int threshold = task.getConsecutiveFailuresThreshold() == null ? 2 : task.getConsecutiveFailuresThreshold();

        // SSL 即将过期独立分支
        if (result.isSslExpiringSoon()) {
            Boolean alerted = sslAlertedTasks.get(task.getId());
            if (!Boolean.TRUE.equals(alerted)) {
                sslAlertedTasks.put(task.getId(), Boolean.TRUE);
                publishSslExpiringAlert(task, result);
            }
        } else if (result.isSuccess() && result.getSslDaysRemaining() != null) {
            // 证书已经续期或者剩余天数 > 阈值，重置 SSL 告警状态以便下次过期能再次触发
            sslAlertedTasks.remove(task.getId());
        }

        if (result.isSuccess()) {
            counter.set(0);
            return;
        }
        int current = counter.incrementAndGet();
        log.debug("探测任务 id={} name={} 连续失败 {}/{} reason={}",
                task.getId(), task.getName(), current, threshold, result.getErrorMessage());
        if (current >= threshold) {
            publishFailureAlert(task, result, current);
            // 重置计数器：避免每次 tick 重复投递；下次再失败累计同样达到阈值才会再投
            counter.set(0);
        }
    }

    /**
     * 投递"连续失败"告警事件到 notification 队列。
     */
    private void publishFailureAlert(ProbeTask task, ProbeResult result, int currentFailures) {
        try {
            String message = String.format("探测任务[%s] 连续失败 %d 次，最近错误：%s",
                    task.getName(), currentFailures,
                    result.getErrorMessage() == null ? "未知" : result.getErrorMessage());
            AlertEvent event = AlertEvent.builder()
                    .ruleId(null)
                    .historyId(null)
                    .clientId(null)
                    .clientName(task.getName())
                    .metric("probe")
                    .operator("fail")
                    .threshold(task.getConsecutiveFailuresThreshold() == null
                            ? null : task.getConsecutiveFailuresThreshold().doubleValue())
                    .currentValue((double) currentFailures)
                    .level(AlertLevel.WARNING.getColumn())
                    .message(message)
                    .firedAt(LocalDateTime.now())
                    .channelIds(probeService.resolveChannelIds(task))
                    .build();
            rabbitTemplate.convertAndSend(Const.MQ_NOTIFICATION, event);
            log.info("探测任务 id={} name={} 触发失败告警 currentFailures={}",
                    task.getId(), task.getName(), currentFailures);
        } catch (Exception e) {
            log.error("投递探测失败告警异常 taskId={} reason={}", task.getId(), e.getMessage());
        }
    }

    /**
     * 投递"SSL 即将过期"告警事件。
     */
    private void publishSslExpiringAlert(ProbeTask task, ProbeResult result) {
        try {
            int days = result.getSslDaysRemaining() == null ? 0 : result.getSslDaysRemaining();
            int warn = task.getSslWarnDays() == null ? 30 : task.getSslWarnDays();
            String message = String.format("探测任务[%s] SSL 证书剩余 %d 天，即将过期（阈值 %d 天）",
                    task.getName(), days, warn);
            AlertEvent event = AlertEvent.builder()
                    .ruleId(null)
                    .historyId(null)
                    .clientId(null)
                    .clientName(task.getName())
                    .metric("probe")
                    .operator("fail")
                    .threshold((double) warn)
                    .currentValue((double) days)
                    .level(AlertLevel.WARNING.getColumn())
                    .message(message)
                    .firedAt(LocalDateTime.now())
                    .channelIds(probeService.resolveChannelIds(task))
                    .build();
            rabbitTemplate.convertAndSend(Const.MQ_NOTIFICATION, event);
            log.info("探测任务 id={} name={} 触发 SSL 即将过期告警 sslDays={}",
                    task.getId(), task.getName(), days);
        } catch (Exception e) {
            log.error("投递 SSL 即将过期告警异常 taskId={} reason={}", task.getId(), e.getMessage());
        }
    }

    // ==== Visible for testing ====

    /**
     * 测试辅助：清空内部状态。
     */
    public void clearStateForTest() {
        failureCounters.clear();
        lastRunAt.clear();
        sslAlertedTasks.clear();
    }

    /**
     * 测试辅助：读取连续失败计数。
     */
    public int failureCounterForTest(Long taskId) {
        AtomicInteger v = failureCounters.get(taskId);
        return v == null ? 0 : v.get();
    }

    /**
     * 测试辅助：读取 SSL 已告警状态。
     */
    public boolean sslAlertedForTest(Long taskId) {
        return Boolean.TRUE.equals(sslAlertedTasks.get(taskId));
    }
}
