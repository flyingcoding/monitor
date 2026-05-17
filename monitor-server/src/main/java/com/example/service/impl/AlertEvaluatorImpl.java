package com.example.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.config.SseEventBus;
import com.example.entity.alert.AlertEvent;
import com.example.entity.alert.AlertLevel;
import com.example.entity.alert.AlertMetric;
import com.example.entity.alert.AlertOperator;
import com.example.entity.alert.AlertStatus;
import com.example.entity.dto.AlertHistory;
import com.example.entity.dto.AlertRule;
import com.example.entity.dto.Client;
import com.example.entity.dto.ClientDetail;
import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.entity.vo.response.AlertHistoryVO;
import com.example.mapper.ClientDetailMapper;
import com.example.mapper.struct.AlertStructMapper;
import com.example.service.AlertEvaluator;
import com.example.service.AlertHistoryService;
import com.example.service.AlertRuleService;
import com.example.service.ClientService;
import com.example.utils.Const;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 阈值告警评估器实现。
 * <p>
 * 在 {@code ClientServiceImpl.updateRuntimeDetail} 完成 SSE 推送后被调用，
 * 异步遍历适用规则并维护 {@link AlertWindowCache} 滚动窗口。
 * 评估流程：
 * <ol>
 *   <li>从规则表查询 {@code enabled=1 AND (client_id = ? OR client_id IS NULL)}；</li>
 *   <li>跳过 {@code silence_until} 未过期的规则；</li>
 *   <li>用 {@link AlertOperator#test(Double, Double)} 计算本次评估结果，写入窗口；</li>
 *   <li>持续满足触发条件且该 (rule, client) 没有未结束的活跃历史（firing / acknowledged）
 *       → 创建新历史 + 投通知；</li>
 *   <li>持续不满足触发条件且存在活跃历史 → 将历史标为 resolved（含已被用户确认 acknowledged 的告警）。</li>
 * </ol>
 * <p>
 * 并发安全：在虚拟线程下，同一客户端的多轮上报可能并发到达；触发分支与 resolve 分支必须
 * 以 (ruleId, clientId) 为粒度串行化，避免重复创建 firing 历史或丢失 resolve 状态。
 * 通过 {@link AlertWindowCache#lockFor(Long, Integer)} 拿到的 entry-level 锁完成临界段保护。
 */
@Slf4j
@Service
public class AlertEvaluatorImpl implements AlertEvaluator {

    @Resource
    private AlertRuleService alertRuleService;

    @Resource
    private AlertHistoryService alertHistoryService;

    @Resource
    @Qualifier("notificationRabbitTemplate")
    private AmqpTemplate rabbitTemplate;

    @Lazy
    @Resource
    private ClientService clientService;

    @Resource
    private AlertWindowCache windowCache;

    @Resource
    private SseEventBus sseEventBus;

    @Resource
    private AlertStructMapper alertStructMapper;

    @Resource
    private ClientDetailMapper clientDetailMapper;

    /**
     * 异步评估当前客户端的实时指标是否触发任何启用规则。
     * <p>
     * 使用 {@link Async @Async} + {@code alertTaskExecutor}（虚拟线程）执行，
     * 避免阻塞 {@code ClientServiceImpl.updateRuntimeDetail} 的客户端上报链路。
     *
     * @param clientId 客户端ID
     * @param runtime  当前实时指标
     */
    @Override
    @Async("alertTaskExecutor")
    public void evaluate(Integer clientId, RuntimeDetailVO runtime) {
        if (clientId == null || runtime == null) {
            return;
        }
        try {
            List<AlertRule> rules = listApplicableRules(clientId);
            if (rules.isEmpty()) {
                return;
            }
            // 在入口处一次性加载 ClientDetail，避免每条规则评估时 N+1 查询
            ClientDetail clientDetail = loadClientDetail(clientId);
            Date now = new Date();
            for (AlertRule rule : rules) {
                evaluateRule(rule, clientId, runtime, clientDetail, now);
            }
        } catch (Exception e) {
            log.warn("告警评估异常 clientId={}, reason={}", clientId, e.getMessage());
        }
    }

    /**
     * 安全加载客户端静态详情（含总内存 / 总磁盘），用于把 GB 用量换算为百分比。
     * <p>
     * 查询失败时返回 null，对应指标无法归一为百分比则跳过评估（避免错误告警）。
     *
     * @param clientId 客户端ID
     * @return 客户端详情，加载失败时返回 null
     */
    private ClientDetail loadClientDetail(Integer clientId) {
        try {
            return clientDetailMapper.selectById(clientId);
        } catch (Exception e) {
            log.warn("加载客户端详情失败 clientId={}, reason={}", clientId, e.getMessage());
            return null;
        }
    }

    /**
     * 查询适用于该客户端的启用规则：精确绑定该客户端 + 全局规则（client_id IS NULL）。
     *
     * @param clientId 客户端ID
     * @return 规则列表，无规则时返回空集合
     */
    private List<AlertRule> listApplicableRules(Integer clientId) {
        try {
            return alertRuleService.list(Wrappers.<AlertRule>lambdaQuery()
                    .eq(AlertRule::getEnabled, true)
                    .and(w -> w.eq(AlertRule::getClientId, clientId).or().isNull(AlertRule::getClientId)));
        } catch (Exception e) {
            log.warn("查询告警规则失败 clientId={}, reason={}", clientId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 对单条规则执行：取值 → 比较 → 窗口记录 → 触发/恢复。
     * <p>
     * 触发/恢复段以 (ruleId, clientId) 级锁串行化，避免虚拟线程并发评估时
     * 重复创建 firing 历史，或一边创建一边 resolve 形成竞态。
     *
     * @param rule         告警规则
     * @param clientId     客户端ID
     * @param runtime      实时指标
     * @param clientDetail 客户端静态详情（含总内存 / 总磁盘），可能为 null
     * @param now          本次评估时间
     */
    private void evaluateRule(AlertRule rule, Integer clientId, RuntimeDetailVO runtime,
                              ClientDetail clientDetail, Date now) {
        if (isSilenced(rule, now)) {
            return;
        }
        AlertMetric metric = AlertMetric.fromColumn(rule.getMetric());
        AlertOperator operator = AlertOperator.fromColumn(rule.getOperator());
        if (metric == null || operator == null) {
            log.warn("告警规则配置异常 ruleId={}, metric={}, operator={}",
                    rule.getId(), rule.getMetric(), rule.getOperator());
            return;
        }
        Double currentValue = extractMetricValue(metric, runtime, clientDetail);
        if (currentValue == null) {
            // 内存/磁盘百分比需要 ClientDetail 总量；缺失时跳过本规则评估，避免误告警
            log.debug("跳过规则评估（缺少客户端总量数据） ruleId={}, clientId={}, metric={}",
                    rule.getId(), clientId, rule.getMetric());
            return;
        }
        boolean met = operator.test(currentValue, rule.getThreshold());
        int durationSec = rule.getDurationSec() == null ? 60 : rule.getDurationSec();
        windowCache.record(rule.getId(), clientId, met, durationSec);

        // 持有 (ruleId, clientId) 级锁后再做窗口判断 + 写库 + 投通知，保证临界段原子
        Object lock = windowCache.lockFor(rule.getId(), clientId);
        synchronized (lock) {
            if (windowCache.isContinuouslyMet(rule.getId(), clientId, durationSec)) {
                fireIfAbsent(rule, clientId, currentValue, now);
            } else if (windowCache.isContinuouslyNotMet(rule.getId(), clientId, durationSec)) {
                resolveIfActive(rule, clientId, now);
            }
        }
    }

    /**
     * 检查规则是否处于静默期。
     *
     * @param rule 告警规则
     * @param now  当前时间
     * @return 静默中返回 true
     */
    private boolean isSilenced(AlertRule rule, Date now) {
        Date silenceUntil = rule.getSilenceUntil();
        return silenceUntil != null && silenceUntil.after(now);
    }

    /**
     * 从实时指标 VO 中取出对应指标的数值，并按规则阈值的语义归一为可比较的单位。
     * <p>
     * 客户端实际上报的语义（参见 {@code MonitorUtils.monitorRuntimeDetail}）：
     * <ul>
     *   <li>{@code cpuUsage}：0~1 的比例数（如 0.9 = 90%）</li>
     *   <li>{@code memoryUsage}：已用内存（GB）</li>
     *   <li>{@code diskUsage}：已用磁盘（GB）</li>
     *   <li>{@code networkUpload} / {@code networkDownload}：速率（KB/s）</li>
     * </ul>
     * 前端在 {@code RuleView.vue} 中以百分比形式配置 CPU / 内存 / 磁盘的阈值（0~100），
     * 因此评估时需要把上报值统一换算为百分比再与阈值比较；网络指标保留 KB/s 原始单位。
     *
     * @param metric       指标枚举
     * @param runtime      实时指标 VO
     * @param clientDetail 客户端静态详情（提供总内存 / 总磁盘），可能为 null
     * @return 与规则阈值同单位的当前值，无法计算时返回 null
     */
    private Double extractMetricValue(AlertMetric metric, RuntimeDetailVO runtime, ClientDetail clientDetail) {
        return switch (metric) {
            // 0~1 -> 0~100 百分比
            case CPU -> runtime.getCpuUsage() * 100.0;
            // 已用 GB -> 百分比；缺总量时返回 null 跳过
            case MEMORY -> toPercent(runtime.getMemoryUsage(),
                    clientDetail == null ? 0.0 : clientDetail.getMemory());
            case DISK -> toPercent(runtime.getDiskUsage(),
                    clientDetail == null ? 0.0 : clientDetail.getDisk());
            // 网络速率保留 KB/s 原始单位，不做归一
            case NETWORK_UP -> runtime.getNetworkUpload();
            case NETWORK_DOWN -> runtime.getNetworkDownload();
        };
    }

    /**
     * 将"已用量 / 总量"换算为百分比，总量缺失或不合法时返回 null。
     *
     * @param used  已用量
     * @param total 总量
     * @return 百分比 (0~100+)，无法计算时返回 null
     */
    private Double toPercent(double used, double total) {
        if (total <= 0.0) {
            return null;
        }
        return used / total * 100.0;
    }

    /**
     * 持续满足条件时触发告警：若已有活跃历史（firing 或已被用户 acknowledged 但未 resolve）
     * 则跳过，避免重复发火。
     *
     * @param rule         告警规则
     * @param clientId     客户端ID
     * @param currentValue 当前值
     * @param now          触发时间
     */
    private void fireIfAbsent(AlertRule rule, Integer clientId, Double currentValue, Date now) {
        AlertHistory existing = findLatestActive(rule.getId(), clientId);
        if (existing != null) {
            return;
        }
        AlertHistory history = buildHistory(rule, clientId, currentValue, now);
        boolean saved = alertHistoryService.save(history);
        if (!saved || history.getId() == null) {
            log.warn("告警历史落库失败 ruleId={}, clientId={}", rule.getId(), clientId);
            return;
        }
        log.info("告警触发 ruleId={}, clientId={}, metric={}, currentValue={}, threshold={}",
                rule.getId(), clientId, rule.getMetric(), currentValue, rule.getThreshold());
        publishNotification(rule, clientId, history, currentValue);
        publishSseAlert(rule, history);
    }

    /**
     * 持续不满足条件时自动 resolve：将处于活跃状态（firing 或 acknowledged）的最新历史
     * 标为 resolved，并记录 resolvedAt。
     * <p>
     * 含 acknowledged 是必要的：用户在 UI 点了"确认"后状态变成 acknowledged，但实际告警条件
     * 可能仍未恢复；如果只针对 firing 自动 resolve，acknowledged 的历史会永远停留在活跃状态。
     *
     * @param rule     告警规则
     * @param clientId 客户端ID
     * @param now      恢复时间
     */
    private void resolveIfActive(AlertRule rule, Integer clientId, Date now) {
        AlertHistory active = findLatestActive(rule.getId(), clientId);
        if (active == null) {
            return;
        }
        String previousStatus = active.getStatus();
        active.setStatus(AlertStatus.RESOLVED.getColumn());
        active.setResolvedAt(now);
        boolean updated = alertHistoryService.updateById(active);
        if (updated) {
            log.info("告警自动恢复 ruleId={}, clientId={}, historyId={}, previousStatus={}",
                    rule.getId(), clientId, active.getId(), previousStatus);
        }
    }

    /**
     * 查询该 (ruleId, clientId) 最新一条活跃状态（firing 或 acknowledged）的告警历史。
     * <p>
     * acknowledged 必须视为活跃：用户点了确认 ≠ 故障恢复；此时评估器仍应将其视为已有告警，
     * 不能重复创建 firing 历史，也应在条件恢复时自动 resolve。
     *
     * @param ruleId   规则ID
     * @param clientId 客户端ID
     * @return 历史记录，无则返回 null
     */
    private AlertHistory findLatestActive(Long ruleId, Integer clientId) {
        try {
            List<String> activeStatuses = Arrays.asList(
                    AlertStatus.FIRING.getColumn(),
                    AlertStatus.ACKNOWLEDGED.getColumn());
            return alertHistoryService.getOne(Wrappers.<AlertHistory>lambdaQuery()
                    .eq(AlertHistory::getRuleId, ruleId)
                    .eq(AlertHistory::getClientId, clientId)
                    .in(AlertHistory::getStatus, activeStatuses)
                    .orderByDesc(AlertHistory::getFiredAt)
                    .last("limit 1"), false);
        } catch (Exception e) {
            log.warn("查询活跃告警历史失败 ruleId={}, clientId={}, reason={}", ruleId, clientId, e.getMessage());
            return null;
        }
    }

    /**
     * 构造一条新的 firing 告警历史。
     *
     * @param rule         告警规则
     * @param clientId     客户端ID
     * @param currentValue 当前值
     * @param firedAt      触发时间
     * @return 历史实体
     */
    private AlertHistory buildHistory(AlertRule rule, Integer clientId, Double currentValue, Date firedAt) {
        AlertHistory history = new AlertHistory();
        history.setRuleId(rule.getId());
        history.setClientId(clientId);
        history.setFiredAt(firedAt);
        history.setStatus(AlertStatus.FIRING.getColumn());
        history.setLevel(rule.getLevel() == null ? AlertLevel.WARNING.getColumn() : rule.getLevel());
        history.setCurrentValue(currentValue);
        history.setMessage(buildMessage(rule, clientId, currentValue));
        return history;
    }

    /**
     * 构造默认告警文案，通知通道可使用自身模板覆盖。
     *
     * @param rule         告警规则
     * @param clientId     客户端ID
     * @param currentValue 当前值
     * @return 告警文案
     */
    private String buildMessage(AlertRule rule, Integer clientId, Double currentValue) {
        return String.format(Locale.ROOT,
                "[%s] 规则[%s] 客户端#%d 指标 %s 当前值 %s %s 阈值 %s",
                String.valueOf(rule.getLevel()).toUpperCase(Locale.ROOT),
                rule.getName(),
                clientId,
                rule.getMetric(),
                formatDouble(currentValue),
                rule.getOperator(),
                formatDouble(rule.getThreshold()));
    }

    /**
     * 数值格式化，避免 null 显示为字面字符串。
     *
     * @param value 数值
     * @return 格式化字符串
     */
    private String formatDouble(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.4f", value);
    }

    /**
     * 投递告警事件到 RabbitMQ "notification" 队列。
     *
     * @param rule         告警规则
     * @param clientId     客户端ID
     * @param history      已落库历史
     * @param currentValue 当前值
     */
    private void publishNotification(AlertRule rule, Integer clientId, AlertHistory history, Double currentValue) {
        try {
            AlertEvent event = AlertEvent.builder()
                    .ruleId(rule.getId())
                    .historyId(history.getId())
                    .clientId(clientId)
                    .clientName(resolveClientName(clientId))
                    .metric(rule.getMetric())
                    .operator(rule.getOperator())
                    .threshold(rule.getThreshold())
                    .currentValue(currentValue)
                    .level(history.getLevel())
                    .message(history.getMessage())
                    .firedAt(toLocalDateTime(history.getFiredAt()))
                    .channelIds(rule.getChannelIds())
                    .build();
            rabbitTemplate.convertAndSend(Const.MQ_NOTIFICATION, event);
        } catch (Exception e) {
            log.error("投递告警通知到 RabbitMQ 失败 ruleId={}, clientId={}, reason={}",
                    rule.getId(), clientId, e.getMessage());
        }
    }

    /**
     * 安全获取客户端名称，未命中缓存时返回 null（事件消费方应容忍）。
     *
     * @param clientId 客户端ID
     * @return 客户端名称
     */
    private String resolveClientName(Integer clientId) {
        try {
            Client client = clientService.findClientById(clientId);
            return client == null ? null : client.getName();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Date → LocalDateTime 转换，null 安全。
     *
     * @param date 日期
     * @return LocalDateTime 或 null
     */
    private java.time.LocalDateTime toLocalDateTime(Date date) {
        return date == null ? null
                : java.time.LocalDateTime.ofInstant(date.toInstant(), java.time.ZoneId.systemDefault());
    }

    /**
     * 通过 {@link SseEventBus} 将刚触发的告警实时推送给前端订阅者。
     * <p>
     * 失败仅记录日志，不抛出异常，避免影响后续通知队列投递。前端订阅 {@code /api/sse/alerts}
     * 接收 {@code alert-fired} 事件，载荷为 {@link AlertHistoryVO}。
     *
     * @param rule    触发的规则
     * @param history 已落库的告警历史
     */
    private void publishSseAlert(AlertRule rule, AlertHistory history) {
        try {
            AlertHistoryVO vo = alertStructMapper.toHistoryVO(history);
            if (rule != null) {
                vo.setRuleName(rule.getName());
            }
            sseEventBus.publishAlertFired(vo);
        } catch (Exception e) {
            log.warn("告警 SSE 推送失败 ruleId={}, historyId={}, reason={}",
                    rule == null ? null : rule.getId(),
                    history == null ? null : history.getId(),
                    e.getMessage());
        }
    }
}
