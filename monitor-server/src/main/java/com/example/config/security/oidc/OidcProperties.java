package com.example.config.security.oidc;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * {@code monitor.oidc.*} 配置开关。
 *
 * <p>对应 prd.md D3：是否允许首登自动创建账号、是否要求 email_verified、是否按 email 关联老账号、默认角色。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "monitor.oidc")
public class OidcProperties {

    /**
     * 总开关。{@code false} 时仍提供 Provider CRUD（管理员可预配置），但 oauth2Login 链路实际不会路由命中。
     * <p>当前 SecurityConfiguration 已无条件注册 {@code oauth2Login}，开关只通过 {@code /api/oidc/providers/public}
     * 列表过滤来影响前端是否渲染按钮。
     */
    private boolean enabled = false;

    /**
     * 邮箱未注册时是否自动创建账号。
     * <p>默认 {@code false}，符合企业部署安全边界（D3）。
     */
    private boolean autoCreateUser = false;

    /**
     * 邮箱关联是否要求 {@code email_verified=true}。
     * <p>默认 {@code true}，避免 IdP 端伪造邮箱冒充。
     */
    private boolean requireEmailVerified = true;

    /**
     * 已存在的本地账号是否允许通过邮箱匹配自动绑定。
     * <p>默认 {@code true}：减少老用户首登摩擦。
     */
    private boolean linkExistingByEmail = true;

    /**
     * 自动建号时分配的角色。
     */
    private String defaultRole = "user";
}
