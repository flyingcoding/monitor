package com.example.entity.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 账号 ↔ OIDC Provider 绑定关系。对应 {@code account_oidc_binding} 表。
 *
 * <p>关联键为 {@code (providerName, subject)}（IdP 内 sub 全局唯一），不依赖 email 变更。
 * 一个账号可以绑定多个 Provider；一个 (provider, sub) 仅能映射到一个账号。
 */
@Data
@TableName("account_oidc_binding")
public class AccountOidcBinding {

    @TableId(type = IdType.AUTO)
    Long id;

    Integer accountId;

    String providerName;

    /**
     * OIDC {@code sub} 声明，IdP 内全局唯一稳定标识。
     */
    String subject;

    /**
     * 绑定时 IdP 返回的邮箱，仅用于审计展示，登录关联以 (provider, subject) 为准。
     */
    String email;

    Date boundAt;
}
