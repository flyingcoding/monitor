package com.example.entity.vo.response;

import lombok.Data;

import java.util.Date;

/**
 * 用户个人设置页"已绑定的 OIDC"列表项。
 */
@Data
public class OidcBindingVO {
    String providerName;
    /**
     * Provider 的显示名（来自 {@code oidc_provider.display_name}），Provider 已被管理员删除时回退到 {@code providerName}。
     */
    String displayName;
    String email;
    Date boundAt;
}
