package com.example.entity.vo.response;

import lombok.Data;

import java.util.Date;

/**
 * 告警历史查询响应VO。ruleName 通过 service 层 join alert_rule.name 后填充，可能为空。
 */
@Data
public class AlertHistoryVO {
    Long id;
    Long ruleId;
    String ruleName;
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
