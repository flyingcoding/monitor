package com.example.entity.vo.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

import java.util.Map;

/**
 * 通知通道更新请求VO。前端如未填写 _enc 字段（已遮罩为 *** 显示），service 层需保留原密文。
 */
@Data
public class NotificationChannelUpdateVO {
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
