package com.example.entity.vo.request;

import com.example.entity.alert.AlertMetric;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

import java.util.Date;
import java.util.List;

/**
 * 告警规则创建请求VO。client_id 为空表示全局规则。
 */
@Data
public class AlertRuleCreateVO {
    @NotBlank
    @Length(max = 64)
    String name;
    /** 客户端ID；为空表示全局规则（对所有客户端生效）。 */
    Integer clientId;
    @NotBlank
    @Pattern(regexp = AlertMetric.VALID_COLUMNS_PATTERN)
    String metric;
    @NotBlank
    @Pattern(regexp = "gt|lt|gte|lte")
    String operator;
    @NotNull
    @DecimalMin("0")
    @DecimalMax("100000")
    Double threshold;
    @NotNull
    @Min(10)
    @Max(86400)
    Integer durationSec;
    @NotBlank
    @Pattern(regexp = "info|warning|critical")
    String level;
    @NotNull
    Boolean enabled;
    /** 关联通知通道ID列表；可为空。 */
    List<Long> channelIds;
    /** 静默截止时间；可为空。 */
    Date silenceUntil;
}
