package com.example.entity.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 告警规则实体类。channel_ids 列存 JSON 数组，由 JacksonTypeHandler 在读写时与
 * {@link List} 互转；@TableName(autoResultMap = true) 保证 SELECT 时类型处理器生效。
 */
@Data
@TableName(value = "alert_rule", autoResultMap = true)
public class AlertRule {
    @TableId(type = IdType.AUTO)
    Long id;
    String name;
    Integer clientId;
    String metric;
    String operator;
    Double threshold;
    Integer durationSec;
    String level;
    Boolean enabled;
    @TableField(typeHandler = JacksonTypeHandler.class)
    List<Long> channelIds;
    Date silenceUntil;
    Date createdAt;
    Date updatedAt;
}
