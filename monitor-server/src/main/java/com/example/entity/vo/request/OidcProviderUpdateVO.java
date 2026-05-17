package com.example.entity.vo.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

/**
 * 更新 OIDC Provider 请求体。
 *
 * <p>{@code clientSecret} 为可选项：null/空字符串 视为不修改，沿用旧密文；
 * 提供新值时由 Service 层加密替换。
 *
 * <p>{@code name} 不允许修改（因关联 {@code account_oidc_binding.provider_name} 与 Spring Security registrationId）。
 */
@Data
public class OidcProviderUpdateVO {

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

    /**
     * 可选；为空/null 表示沿用旧密文（参考 {@code preserveExistingEnc} 同名约定）。
     */
    @Length(max = 255)
    String clientSecret;

    @NotBlank
    @Length(max = 255)
    String scopes;

    @NotNull
    Boolean enabled;
}
