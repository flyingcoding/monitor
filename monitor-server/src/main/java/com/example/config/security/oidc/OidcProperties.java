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
     * 总开关（全局 kill-switch）。{@code false} 时：
     * <ul>
     *   <li>{@code /api/oidc/providers/public} 返回空数组，前端登录页不渲染按钮；</li>
     *   <li>{@code DelegatingClientRegistrationRepository#findByRegistrationId} 返回 null，
     *       Spring Security 让 {@code /oauth2/authorization/<any>} 直接 404，
     *       阻断猜测 registrationId 启动 OAuth 流程的攻击面。</li>
     * </ul>
     * <p>关闭时 Provider CRUD 仍可用（管理员可预配置 IdP，再切换开关上线）。
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
