package com.example.entity.vo.response;

import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 告警规则查询响应VO。
 */
@Data
public class AlertRuleVO {
    Long id;
    String name;
    Integer clientId;
    String metric;
    String operator;
    Double threshold;
    Integer durationSec;
    String level;
    Boolean enabled;
    List<Long> channelIds;
    Date silenceUntil;
    Date createdAt;
    Date updatedAt;
}
