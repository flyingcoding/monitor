package com.example.entity.vo.response;

import lombok.Data;

import java.util.Date;

/**
 * 管理员视角的 OIDC Provider VO。
 *
 * <p>永远不会返回 {@code clientSecret} 明文；{@code hasSecret} 用于前端判断"是否已设置密钥"。
 */
@Data
public class OidcProviderVO {
    Long id;
    String name;
    String displayName;
    String iconUrl;
    String issuerUrl;
    String clientId;
    String scopes;
    Boolean enabled;
    /**
     * 是否已设置 client secret。前端据此渲染"已配置 / 留空保留"提示。
     */
    Boolean hasSecret;
    Date createdAt;
    Date updatedAt;
}
