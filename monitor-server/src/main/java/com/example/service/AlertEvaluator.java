package com.example.service;

import com.example.entity.vo.request.RuntimeDetailVO;

/**
 * 阈值告警评估器接口。挂在 {@code ClientServiceImpl.updateRuntimeDetail} 之后异步触发。
 * 实现类由 Agent A（阈值引擎模块）落地，应满足：
 * <ul>
 *   <li>对每个 (rule_id, client_id) 维护内存滚动窗口；</li>
 *   <li>持续 {@code duration_sec} 满足条件时创建 {@code alert_history} 记录并投递通知事件；</li>
 *   <li>持续不满足条件时自动 resolve；</li>
 *   <li>规则 {@code silence_until} 未过期前不触发新告警。</li>
 * </ul>
 */
public interface AlertEvaluator {

    /**
     * 评估当前客户端的实时指标是否触发任何启用规则。
     *
     * @param clientId 客户端ID
     * @param runtime  当前实时指标（来自 RuntimeDetailVO，避免反查 InfluxDB）
     */
    void evaluate(Integer clientId, RuntimeDetailVO runtime);
}
