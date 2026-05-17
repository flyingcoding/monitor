package com.example.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.entity.dto.AlertRule;
import com.example.mapper.AlertRuleMapper;
import com.example.service.AlertRuleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 告警规则 Service 实现。提供基础 CRUD 与一处便捷查询：按客户端筛选启用规则
 * （含 client_id 为 NULL 的全局规则）供 AlertEvaluator 评估时使用。
 */
@Slf4j
@Service
public class AlertRuleServiceImpl extends ServiceImpl<AlertRuleMapper, AlertRule> implements AlertRuleService {

    @Override
    public List<AlertRule> listEnabledRules(Integer clientId) {
        return this.list(Wrappers.<AlertRule>lambdaQuery()
                .eq(AlertRule::getEnabled, true)
                .and(w -> w.eq(AlertRule::getClientId, clientId).or().isNull(AlertRule::getClientId)));
    }
}
