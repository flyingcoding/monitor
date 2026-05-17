package com.example.config.security.oidc;

import org.springframework.security.core.AuthenticationException;

/**
 * OIDC 登录链路的认证异常。
 *
 * <p>用于 {@code AccountService.resolveOrCreateByOidc} / {@code OidcSuccessHandler} 在判定无法关联
 * 现有账号且策略不允许自动建号时抛出；由 {@link OidcFailureHandler} 转换为可读错误。
 *
 * <p>不直接抛 {@link RuntimeException}，因为 Spring Security 的失败 handler 只会被 {@link AuthenticationException}
 * 触发，其他异常会冒泡到 ValidationController。
 */
public class OidcLoginException extends AuthenticationException {

    /**
     * 业务错误码（参考 {@code OidcLoginErrorCode}）。
     */
    private final String errorCode;

    public OidcLoginException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
