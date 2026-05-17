package com.example.entity.vo.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

/**
 * 创建 OIDC Provider 请求体。
 *
 * <p>{@code clientSecret} 为明文密码（必填），Service 层加密后入库；
 * {@code name} 用作 Spring Security registrationId，仅允许小写字母/数字/短横线。
 */
@Data
public class OidcProviderCreateVO {
    @NotBlank
    @Pattern(regexp = "^[a-z0-9-]+$", message = "name 仅允许小写字母、数字、短横线")
    @Length(max = 64)
    String name;

    @Length(max = 128)
    String displayName;

    @Length(max = 255)
    String iconUrl;

    @NotBlank
    @Length(max = 255)
    String issuerUrl;

    @NotBlank
    @Length(max = 255)
    String clientId;

    @NotBlank
    @Length(max = 255)
    String clientSecret;

    @NotBlank
    @Length(max = 255)
    String scopes;

    @NotNull
    Boolean enabled;
}
