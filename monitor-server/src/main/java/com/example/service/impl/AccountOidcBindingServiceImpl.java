package com.example.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.entity.dto.Account;
import com.example.entity.dto.AccountOidcBinding;
import com.example.entity.dto.OidcProvider;
import com.example.entity.vo.response.OidcBindingVO;
import com.example.mapper.AccountOidcBindingMapper;
import com.example.service.AccountOidcBindingService;
import com.example.service.AccountService;
import com.example.service.OidcProviderService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 账号 ↔ OIDC Provider 绑定服务实现。
 *
 * <p>"解绑前至少保留一种登录方式" 的安全校验在 {@link #unbind(int, String)} 中实现：
 * <ul>
 *   <li>账号有 password 列（本地密码）— OK 解绑；</li>
 *   <li>没有密码但还有其他 OIDC 绑定 — OK 解绑；</li>
 *   <li>否则拒绝解绑，避免账号变孤儿。</li>
 * </ul>
 */
@Slf4j
@Service
public class AccountOidcBindingServiceImpl
        extends ServiceImpl<AccountOidcBindingMapper, AccountOidcBinding>
        implements AccountOidcBindingService {

    @Resource
    private AccountService accountService;

    // OidcProviderService 仅用于 displayName 回填；通过 Autowired(required=false) 避免循环装配风险
    @Autowired(required = false)
    private OidcProviderService oidcProviderService;

    /**
     * 列出账号下全部绑定，附带 Provider 显示名。
     */
    @Override
    public List<OidcBindingVO> listByAccount(int accountId) {
        List<AccountOidcBinding> rows = this.list(new QueryWrapper<AccountOidcBinding>()
                .eq("account_id", accountId)
                .orderByDesc("bound_at"));
        Map<String, String> displayNameByName = new HashMap<>();
        if (oidcProviderService != null && !rows.isEmpty()) {
            // 一次性查 Provider，避免 N+1
            for (OidcProvider p : oidcProviderService.list()) {
                if (p.getName() != null) {
                    displayNameByName.put(p.getName(),
                            p.getDisplayName() == null ? p.getName() : p.getDisplayName());
                }
            }
        }
        return rows.stream().map(row -> {
            OidcBindingVO vo = new OidcBindingVO();
            vo.setProviderName(row.getProviderName());
            vo.setDisplayName(displayNameByName.getOrDefault(row.getProviderName(), row.getProviderName()));
            vo.setEmail(row.getEmail());
            vo.setBoundAt(row.getBoundAt());
            return vo;
        }).sorted(Comparator.comparing(OidcBindingVO::getBoundAt,
                Comparator.nullsLast(Comparator.reverseOrder()))).toList();
    }

    @Override
    public AccountOidcBinding findByProviderSubject(String provider, String subject) {
        if (provider == null || subject == null) {
            return null;
        }
        return this.getOne(new QueryWrapper<AccountOidcBinding>()
                .eq("provider_name", provider)
                .eq("subject", subject), false);
    }

    /**
     * 幂等 upsert：(provider, subject) 已存在则刷新 email，否则插入新行。
     * 同账号同 provider 的不同 subject 不视为冲突（理论上 sub 唯一，但若 IdP 替换 sub 仍允许追加记录）。
     *
     * <p>对"被其他账号占用"的冲突情况静默忽略并写日志；需要感知冲突的调用方应改用
     * {@link #bindIfFree(int, String, String, String)}。
     */
    @Override
    public void upsert(int accountId, String provider, String subject, String email) {
        bindIfFree(accountId, provider, subject, email);
    }

    /**
     * P2-2：显式返回 {@link BindingResult} 的绑定方法。
     *
     * <ul>
     *   <li>(provider, subject) 未存在 → 新建行，返回 {@link BindingResult#CREATED}；</li>
     *   <li>(provider, subject) 已属当前 accountId → 仅刷新 email，返回 {@link BindingResult#ALREADY_OWNED_BY_SELF}；</li>
     *   <li>(provider, subject) 已属其他账号 → 不修改任何行，返回 {@link BindingResult#CONFLICT}。</li>
     * </ul>
     */
    @Override
    public BindingResult bindIfFree(int accountId, String provider, String subject, String email) {
        AccountOidcBinding existing = this.findByProviderSubject(provider, subject);
        if (existing != null) {
            if (existing.getAccountId() != null && existing.getAccountId() != accountId) {
                log.warn("OIDC 绑定 (provider={}, sub={}) 已被账号 {} 占用，当前请求账号 {} 被拒绝",
                        provider, subject, existing.getAccountId(), accountId);
                return BindingResult.CONFLICT;
            }
            if (email != null && !email.equals(existing.getEmail())) {
                existing.setEmail(email);
                this.updateById(existing);
            }
            return BindingResult.ALREADY_OWNED_BY_SELF;
        }
        AccountOidcBinding row = new AccountOidcBinding();
        row.setAccountId(accountId);
        row.setProviderName(provider);
        row.setSubject(subject);
        row.setEmail(email);
        row.setBoundAt(new Date());
        this.save(row);
        log.info("OIDC 绑定新建 accountId={} provider={}", accountId, provider);
        return BindingResult.CREATED;
    }

    @Override
    public UnbindResult unbind(int accountId, String provider) {
        AccountOidcBinding row = this.getOne(new QueryWrapper<AccountOidcBinding>()
                .eq("account_id", accountId)
                .eq("provider_name", provider), false);
        if (row == null) {
            return UnbindResult.BINDING_NOT_FOUND;
        }
        // 解绑前确保账号至少保留一种登录方式
        Account account = accountService.getById(accountId);
        boolean hasPassword = account != null
                && account.getPassword() != null
                && !account.getPassword().isBlank();
        if (!hasPassword && !this.hasOtherBinding(accountId, provider)) {
            return UnbindResult.LAST_LOGIN_METHOD;
        }
        this.removeById(row.getId());
        log.info("OIDC 解绑 accountId={} provider={}", accountId, provider);
        return UnbindResult.OK;
    }

    @Override
    public boolean hasOtherBinding(int accountId, String excludeProvider) {
        Long count = this.baseMapper.selectCount(new QueryWrapper<AccountOidcBinding>()
                .eq("account_id", accountId)
                .ne("provider_name", excludeProvider));
        return count != null && count > 0;
    }
}
