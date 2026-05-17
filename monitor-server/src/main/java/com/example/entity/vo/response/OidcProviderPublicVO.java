package com.example.entity.vo.response;

import lombok.Data;

/**
 * 公开 OIDC Provider VO，无需鉴权可访问。
 *
 * <p>仅暴露登录页渲染所需的最小字段集合：name（路由 {@code /oauth2/authorization/{name}}）、
 * displayName（按钮文案）、iconUrl（按钮图标）。不暴露 issuer_url / client_id 等内部细节。
 */
@Data
public class OidcProviderPublicVO {
    String name;
    String displayName;
    String iconUrl;
}
