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
}
