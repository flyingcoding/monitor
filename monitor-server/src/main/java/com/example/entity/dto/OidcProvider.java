package com.example.entity.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * OIDC / OAuth2 Provider 实体。对应 {@code oidc_provider} 表。
 *
 * <p>{@code clientSecretEnc} 列以 {@code CryptoUtils} 输出的 {@code ENC:base64(iv|ciphertext)} 形式存储；
 * Service 层在读出时解密、写入时加密，外部消费者不直接接触原始字段。
 */
@Data
@TableName("oidc_provider")
public class OidcProvider {

    @TableId(type = IdType.AUTO)
    Long id;

    /**
     * Provider 唯一标识，对应 Spring Security 的 registrationId（如 "github", "google", "internal"）。
     */
    String name;

    /**
     * 前端登录页展示的名称（"Sign in with GitHub"）。
     */
    String displayName;

    /**
     * 登录页按钮图标 URL。
     */
    String iconUrl;

    /**
     * OIDC Issuer URL，配合 {@code ClientRegistrations.fromIssuerLocation} 自动发现端点。
     */
    String issuerUrl;

    /**
     * OAuth2 Client ID。
     */
    String clientId;

    /**
     * 加密后的 Client Secret（{@code ENC:...} 前缀，由 {@code CryptoUtils} 处理）。
     */
    String clientSecretEnc;

    /**
     * scopes，逗号分隔。如 {@code openid,profile,email}。
     */
    String scopes;

    /**
     * 是否启用：0=禁用，1=启用。
     */
    Boolean enabled;

    Date createdAt;

    Date updatedAt;
}
