package com.example.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.entity.alert.AlertStatus;
import com.example.entity.dto.AlertHistory;
import com.example.entity.dto.AlertRule;
import com.example.mapper.AlertRuleMapper;
import com.example.service.AlertHistoryService;
import com.example.service.AlertRuleService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * 告警规则 Service 实现。提供基础 CRUD 与一处便捷查询：按客户端筛选启用规则
 * （含 client_id 为 NULL 的全局规则）供 AlertEvaluator 评估时使用。
 */
@Slf4j
@Service
public class AlertRuleServiceImpl extends ServiceImpl<AlertRuleMapper, AlertRule> implements AlertRuleService {

    /**
     * 引用 AlertHistoryService 用于规则变更时批量 resolve 活跃告警。
     * Lazy 避免与 AlertHistoryService 之间形成早期初始化循环。
     */
    @Lazy
    @Resource
    private AlertHistoryService alertHistoryService;

    @Override
    public List<AlertRule> listEnabledRules(Integer clientId) {
        return this.list(Wrappers.<AlertRule>lambdaQuery()
                .eq(AlertRule::getEnabled, true)
                .and(w -> w.eq(AlertRule::getClientId, clientId).or().isNull(AlertRule::getClientId)));
    }

    /**
     * 将该规则下所有处于活跃状态（firing / acknowledged）的告警历史批量更新为 resolved。
     * <p>
     * 实现要点：
     * <ul>
     *   <li>resolved_at 统一使用本次调用时刻；</li>
     *   <li>message 末尾追加中文原因，便于前端 / 运维排查；</li>
     *   <li>仅在确实有活跃告警时记录 INFO 日志，避免日志噪声。</li>
     * </ul>
     *
     * @param ruleId       规则ID
     * @param onlyClientId 限定客户端（{@code null} 表示该规则全部客户端）
     * @param reason       resolve 原因（中文）
     * @return 实际 resolve 条数
     */
    @Override
    public int resolveActivesByRule(Long ruleId, Integer onlyClientId, String reason) {
        if (ruleId == null) {
            return 0;
        }
        List<String> activeStatuses = Arrays.asList(
                AlertStatus.FIRING.getColumn(),
                AlertStatus.ACKNOWLEDGED.getColumn());
        List<AlertHistory> actives = alertHistoryService.list(Wrappers.<AlertHistory>lambdaQuery()
                .eq(AlertHistory::getRuleId, ruleId)
                .eq(onlyClientId != null, AlertHistory::getClientId, onlyClientId)
                .in(AlertHistory::getStatus, activeStatuses));
        if (actives.isEmpty()) {
            return 0;
        }
        Date now = new Date();
        String suffix = reason == null || reason.isBlank() ? "" : "（" + reason + "）";
        int updated = 0;
        for (AlertHistory h : actives) {
            h.setStatus(AlertStatus.RESOLVED.getColumn());
            h.setResolvedAt(now);
            String original = h.getMessage() == null ? "" : h.getMessage();
            if (!suffix.isEmpty() && !original.endsWith(suffix)) {
                h.setMessage(original + suffix);
            }
            if (alertHistoryService.updateById(h)) {
                updated++;
            }
        }
        log.info("规则变更触发批量 resolve，ruleId={}, onlyClientId={}, reason={}, affected={}",
                ruleId, onlyClientId, reason, updated);
        return updated;
    }
}
