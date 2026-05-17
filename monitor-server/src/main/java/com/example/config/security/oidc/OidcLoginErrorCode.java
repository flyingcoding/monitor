package com.example.config.security.oidc;

/**
 * OIDC 登录失败错误码常量。前端按 {@code oidc_error} query 参数分发文案。
 */
public final class OidcLoginErrorCode {

    private OidcLoginErrorCode() {
    }

    /**
     * 账号不存在且 auto-create 关闭：邮箱未在 account 表，且策略未允许自动建号。
     */
    public static final String ACCOUNT_NOT_FOUND = "account_not_found";

    /**
     * IdP 返回的 email_verified=false 且策略要求 verified email。
     */
    public static final String EMAIL_NOT_VERIFIED = "email_not_verified";

    /**
     * IdP 未返回 email claim 且策略需要 email 关联。
     */
    public static final String EMAIL_MISSING = "email_missing";

    /**
     * Provider 已被禁用或不存在。
     */
    public static final String PROVIDER_DISABLED = "provider_disabled";

    /**
     * P2-2：登录绑定流程中，(provider, subject) 已被其他账号占用。
     */
    public static final String OIDC_CONFLICT = "oidc_conflict";

    /**
     * 其他不可恢复错误。
     */
    public static final String INTERNAL_ERROR = "internal_error";
}
