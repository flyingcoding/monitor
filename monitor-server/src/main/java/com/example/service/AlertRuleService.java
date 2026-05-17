package com.example.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.entity.dto.AlertRule;

import java.util.List;

/**
 * 告警规则 Service 接口。实现类由 Agent C（告警 Controller 模块）落地。
 * 提供 IService 基础 CRUD 与一处面向 AlertEvaluator 的便捷查询。
 */
public interface AlertRuleService extends IService<AlertRule> {

    /**
     * 查询对指定客户端生效的启用规则：包含 client_id 与该值相等的规则以及
     * client_id IS NULL 的全局规则。
     *
     * @param clientId 目标客户端ID
     * @return 启用规则列表（包含全局规则）
     */
    List<AlertRule> listEnabledRules(Integer clientId);

    /**
     * 将该规则下所有处于活跃状态（firing / acknowledged）的告警历史批量标记为 resolved。
     * <p>
     * 使用场景：管理员修改了规则的关键属性（禁用 / 改作用域 / 改阈值或操作符 /
     * 改持续时间）或删除规则。此时旧告警的语义已不再成立，但评估器主循环不会再遍历
     * 不匹配的规则，无法走"持续不满足 → 自动 resolve"分支，从而导致告警长期挂在
     * 活跃状态。需要在规则变更点显式批量收尾。
     *
     * @param ruleId        规则ID
     * @param onlyClientId  仅 resolve 该客户端的活跃告警（适用于"作用域从单客户端变更"），
     *                      {@code null} 表示该规则下所有客户端的活跃告警都 resolve
     * @param reason        附加在 message 末尾的原因（中文），用于审计与前端排查
     * @return 实际被 resolve 的历史条数
     */
    int resolveActivesByRule(Long ruleId, Integer onlyClientId, String reason);
}
