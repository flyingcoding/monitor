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

import java.util.List;

/**
 * 告警规则更新请求VO。id 通过路径参数传递，不在请求体中。
 * <p>
 * 不含 {@code silenceUntil} 字段：静默时间只允许通过专用端点
 * {@code POST /api/alert/rule/{id}/silence?minutes=N} 修改，避免普通编辑/启停
 * 误传 null 把活跃静默期清空（参见 AlertStructMapper.updateRule 的 ignore 配置作为双保险）。
 */
@Data
public class AlertRuleUpdateVO {
    @NotBlank
    @Length(max = 64)
    String name;
    /** 客户端ID；为空表示全局规则。 */
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
    List<Long> channelIds;
}
