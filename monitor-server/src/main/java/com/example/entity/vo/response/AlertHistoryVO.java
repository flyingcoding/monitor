package com.example.entity.vo.response;

import lombok.Data;

import java.util.Date;

/**
 * 告警历史查询响应VO。ruleName / metric 通过 service / controller 层根据 ruleId 反查 alert_rule 后填充，
 * 可能为空（关联规则被删除时）。
 * <p>
 * metric 不在 alert_history 表中存储，避免规则被修改 metric 后历史与当前规则元数据不一致；
 * 通过 ruleId 反查保证 metric 与规则当前定义保持一致，前端用于决定数值单位（% / KB/s 等）。
 */
@Data
public class AlertHistoryVO {
    Long id;
    Long ruleId;
    String ruleName;
    /** 指标名称（cpu / memory / disk / network_up / network_down），来自关联规则。 */
    String metric;
    Integer clientId;
    Date firedAt;
    Date resolvedAt;
    String status;
    String level;
    Double currentValue;
    String message;
    Integer ackedBy;
    Date ackedAt;
}
