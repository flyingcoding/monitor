package com.example.entity.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 告警历史实体类。记录每次规则触发的生命周期：firing → resolved / acknowledged。
 */
@Data
@TableName("alert_history")
public class AlertHistory {
    @TableId(type = IdType.AUTO)
    Long id;
    Long ruleId;
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
