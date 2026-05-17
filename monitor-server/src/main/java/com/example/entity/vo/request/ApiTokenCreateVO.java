package com.example.entity.vo.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

import java.util.Date;

/**
 * 创建 API Token 请求体。
 *
 * <p>{@code scope} 取值必须为 {@code readonly} 或 {@code readwrite}（v1.2 二档，prd D5）。
 * {@code expiresAt} 为可选字段，{@code null} 表示永不过期。
 */
@Data
public class ApiTokenCreateVO {

    @NotBlank
    @Length(max = 128)
    String name;

    @NotBlank
    @Pattern(regexp = "^(readonly|readwrite)$", message = "scope 仅支持 readonly 或 readwrite")
    String scope;

    /**
     * 可选过期时间；不填表示永不过期。
     */
    Date expiresAt;
}
