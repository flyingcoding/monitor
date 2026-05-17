package com.example.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.entity.dto.AccountOidcBinding;
import com.example.entity.vo.response.OidcBindingVO;

import java.util.List;

/**
 * 账号 ↔ OIDC Provider 绑定关系服务。
 */
public interface AccountOidcBindingService extends IService<AccountOidcBinding> {

    /**
     * 列出某账号的全部绑定。
     *
     * @param accountId 账号ID
     * @return 绑定列表（按绑定时间倒序）
     */
    List<OidcBindingVO> listByAccount(int accountId);

    /**
     * 根据 (provider, subject) 查询绑定。
     *
     * @param provider Provider 名
     * @param subject  OIDC sub 声明
     * @return 绑定实体，找不到返回 null
     */
    AccountOidcBinding findByProviderSubject(String provider, String subject);

    /**
     * 创建或更新一条绑定（subject 已存在则刷新 email）。
     *
     * @param accountId 账号ID
     * @param provider  Provider 名
     * @param subject   OIDC sub
     * @param email     IdP 返回的邮箱（仅审计用）
     */
    void upsert(int accountId, String provider, String subject, String email);

    /**
     * 解除一条绑定。
     *
     * @param accountId 账号ID
     * @param provider  Provider 名
     * @return {@link UnbindResult#OK} 成功；其他枚举值为拒绝原因
     */
    UnbindResult unbind(int accountId, String provider);

    /**
     * 是否还存在除指定 provider 外的其他绑定。
     *
     * @param accountId       账号ID
     * @param excludeProvider 排除的 provider 名
     * @return 存在其他绑定时返回 {@code true}
     */
    boolean hasOtherBinding(int accountId, String excludeProvider);

    /**
     * 解绑结果枚举。控制器据此返回不同错误码 / 文案。
     */
    enum UnbindResult {
        OK,
        BINDING_NOT_FOUND,
        /**
         * 解绑后账号没有任何登录方式（无密码 + 仅剩此唯一绑定）。
         */
        LAST_LOGIN_METHOD
    }
}
