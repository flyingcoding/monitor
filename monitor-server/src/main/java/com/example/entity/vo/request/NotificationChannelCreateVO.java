package com.example.entity.vo.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

import java.util.Map;

/**
 * 通知通道创建请求VO。config 字段中带 _enc 后缀的键会在 service 层加密后入库。
 */
@Data
public class NotificationChannelCreateVO {
    @NotBlank
    @Length(max = 64)
    String name;
    @NotBlank
    @Pattern(regexp = "mail|webhook|dingtalk|feishu")
    String type;
    @NotNull
    Map<String, Object> config;
    @NotNull
    Boolean enabled;
}
