package com.example.entity.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.util.Date;
import java.util.Map;

/**
 * 通知通道实体类。config 列存 JSON 配置（含 _enc 后缀的敏感字段，需在 service 层加解密），
 * 由 JacksonTypeHandler 在读写时与 {@link Map} 互转。
 */
@Data
@TableName(value = "notification_channel", autoResultMap = true)
public class NotificationChannel {
    @TableId(type = IdType.AUTO)
    Long id;
    String name;
    String type;
    @TableField(typeHandler = JacksonTypeHandler.class)
    Map<String, Object> config;
    Boolean enabled;
    Date createdAt;
}
