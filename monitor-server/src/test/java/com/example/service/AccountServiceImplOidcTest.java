package com.example.service;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.config.security.oidc.OidcLoginErrorCode;
import com.example.config.security.oidc.OidcLoginException;
import com.example.config.security.oidc.OidcProperties;
import com.example.entity.dto.Account;
import com.example.entity.dto.AccountOidcBinding;
import com.example.mapper.AccountMapper;
import com.example.mapper.AccountOidcBindingMapper;
import com.example.service.impl.AccountServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AccountServiceImpl.resolveOrCreateByOidc 单元测试。
 *
 * <p>覆盖 D3 策略矩阵：
 * <ol>
 *   <li>(provider, sub) 已绑定 → 直接返回；</li>
 *   <li>email_verified=false 且 requireEmailVerified=true → 抛 EMAIL_NOT_VERIFIED；</li>
 *   <li>linkExistingByEmail=true 命中老账号 → 返回该账号；</li>
 *   <li>autoCreateUser=true 新建账号；</li>
 *   <li>autoCreateUser=false 且未命中 → 抛 ACCOUNT_NOT_FOUND；</li>
 *   <li>autoCreateUser=true 但 email 缺失 → 抛 EMAIL_MISSING。</li>
 * </ol>
 *
 * <p>遵循项目惯例（AlertEvaluatorImplTest）：JDK 动态代理 + ReflectionTestUtils，无 Mockito。
 */
class AccountServiceImplOidcTest {

    private AccountServiceImpl service;
    private final List<Account> accountRows = new ArrayList<>();
    private final List<AccountOidcBinding> bindingRows = new ArrayList<>();
    private final AtomicInteger accountIdSeq = new AtomicInteger(100);
    private OidcProperties oidcProperties;

    @BeforeEach
    void setUp() {
        accountRows.clear();
        bindingRows.clear();
        accountIdSeq.set(100);
        oidcProperties = new OidcProperties();
        oidcProperties.setAutoCreateUser(false);
        oidcProperties.setRequireEmailVerified(true);
        oidcProperties.setLinkExistingByEmail(true);
        oidcProperties.setDefaultRole("user");

        service = new AccountServiceImpl() {

            @Override
            public Account getById(java.io.Serializable id) {
                int i = ((Number) id).intValue();
                for (Account a : accountRows) {
                    if (a.getId() != null && a.getId() == i) return a;
                }
                return null;
            }

            @Override
            public boolean save(Account entity) {
                if (entity.getId() == null) entity.setId(accountIdSeq.getAndIncrement());
                accountRows.add(entity);
                return true;
            }

            @Override
            public Account findAccountByNameOrEmail(String text) {
                for (Account a : accountRows) {
                    if (text == null) continue;
                    if (text.equals(a.getUsername()) || text.equals(a.getEmail())) {
                        return a;
                    }
                }
                return null;
            }

            // resolveOrCreateByOidc 走 findAccountByEmail；这里覆写以避开 query() chain wrapper
            @Override
            protected Account findAccountByEmail(String email) {
                if (email == null) return null;
                for (Account a : accountRows) {
                    if (email.equals(a.getEmail())) return a;
                }
                return null;
            }
        };

        AccountMapper baseMapper = (AccountMapper) Proxy.newProxyInstance(
                AccountMapper.class.getClassLoader(),
                new Class[]{AccountMapper.class},
                (proxy, method, args) -> null);
        ReflectionTestUtils.setField(service, "baseMapper", baseMapper);

        AccountOidcBindingMapper bindingMapper = (AccountOidcBindingMapper) Proxy.newProxyInstance(
                AccountOidcBindingMapper.class.getClassLoader(),
                new Class[]{AccountOidcBindingMapper.class},
                (proxy, method, args) -> {
                    if ("selectOne".equals(method.getName())) {
                        Wrapper<?> w = (Wrapper<?>) args[0];
                        // 通过 AbstractWrapper 暴露的 paramNameValuePairs 拿到 ?-binding 值
                        Map<String, Object> params = ((AbstractWrapper<?, ?, ?>) w).getParamNameValuePairs();
                        // 我们的 Wrappers.<>query().eq("provider_name", p).eq("subject", s) 会按插入顺序绑定参数
                        // 取所有 String 类型按顺序匹配 (provider, subject)
                        String provider = null;
                        String subject = null;
                        for (Map.Entry<String, Object> e : new java.util.TreeMap<>(params).entrySet()) {
                            if (e.getValue() instanceof String s) {
                                if (provider == null) provider = s;
                                else if (subject == null) subject = s;
                            }
                        }
                        for (AccountOidcBinding row : bindingRows) {
                            if (java.util.Objects.equals(row.getProviderName(), provider)
                                    && java.util.Objects.equals(row.getSubject(), subject)) {
                                return row;
                            }
                        }
                    }
                    return null;
                });
        ReflectionTestUtils.setField(service, "accountOidcBindingMapper", bindingMapper);

        ReflectionTestUtils.setField(service, "oidcProperties", oidcProperties);

        PasswordEncoder encoder = (PasswordEncoder) Proxy.newProxyInstance(
                PasswordEncoder.class.getClassLoader(),
                new Class[]{PasswordEncoder.class},
                (proxy, method, args) -> {
                    if ("encode".equals(method.getName())) return "$encoded$" + args[0];
                    if ("matches".equals(method.getName())) return args[0] != null && args[0].equals(args[1]);
                    return null;
                });
        ReflectionTestUtils.setField(service, "passwordEncoder", encoder);
    }

    /**
     * 分支 1：已绑定 → 返回所属账号。
     */
    @Test
    void existingBindingShouldReturnLinkedAccount() {
        Account a = new Account(7, "user7", "pwd", "u7@example.com",
                "user", "[]", new Date(), Boolean.TRUE);
        accountRows.add(a);
        AccountOidcBinding b = new AccountOidcBinding();
        b.setId(1L);
        b.setAccountId(7);
        b.setProviderName("github");
        b.setSubject("sub-7");
        b.setEmail("u7@example.com");
        b.setBoundAt(new Date());
        bindingRows.add(b);

        Account result = service.resolveOrCreateByOidc("github", "sub-7", "u7@example.com", Boolean.TRUE);
        Assertions.assertNotNull(result);
        Assertions.assertEquals(7, result.getId());
    }

    /**
     * 分支 2：email_verified=false 且 require=true 抛 EMAIL_NOT_VERIFIED。
     */
    @Test
    void unverifiedEmailShouldThrowWhenRequireEmailVerified() {
        oidcProperties.setRequireEmailVerified(true);
        OidcLoginException ex = Assertions.assertThrows(OidcLoginException.class, () ->
                service.resolveOrCreateByOidc("github", "sub-1", "u@example.com", Boolean.FALSE));
        Assertions.assertEquals(OidcLoginErrorCode.EMAIL_NOT_VERIFIED, ex.getErrorCode());
    }

    /**
     * 分支 3：linkExistingByEmail 命中 → 返回老账号。
     */
    @Test
    void linkByEmailShouldReturnExistingAccount() {
        oidcProperties.setLinkExistingByEmail(true);
        Account a = new Account(8, "user8", "pwd", "u8@example.com",
                "user", "[]", new Date(), Boolean.TRUE);
        accountRows.add(a);

        Account result = service.resolveOrCreateByOidc("github", "sub-8", "u8@example.com", Boolean.TRUE);
        Assertions.assertNotNull(result);
        Assertions.assertEquals(8, result.getId());
    }

    /**
     * 分支 4：autoCreateUser=true 时新建账号。
     */
    @Test
    void autoCreateUserShouldInsertNewAccount() {
        oidcProperties.setAutoCreateUser(true);
        oidcProperties.setLinkExistingByEmail(false);

        Account result = service.resolveOrCreateByOidc("github", "sub-9", "new@example.com", Boolean.TRUE);
        Assertions.assertNotNull(result);
        Assertions.assertEquals("new@example.com", result.getEmail());
        Assertions.assertEquals("user", result.getRole());
        Assertions.assertNull(result.getPassword());
        Assertions.assertEquals(1, accountRows.size());
    }

    /**
     * 分支 5：autoCreateUser=false 且未命中 → 抛 ACCOUNT_NOT_FOUND。
     */
    @Test
    void noAutoCreateAndNoMatchShouldThrow() {
        oidcProperties.setAutoCreateUser(false);
        oidcProperties.setLinkExistingByEmail(false);
        OidcLoginException ex = Assertions.assertThrows(OidcLoginException.class, () ->
                service.resolveOrCreateByOidc("github", "sub-10", "unknown@example.com", Boolean.TRUE));
        Assertions.assertEquals(OidcLoginErrorCode.ACCOUNT_NOT_FOUND, ex.getErrorCode());
    }

    /**
     * autoCreateUser=true 但 email 为空时抛 EMAIL_MISSING。
     */
    @Test
    void autoCreateWithoutEmailShouldThrowEmailMissing() {
        oidcProperties.setAutoCreateUser(true);
        oidcProperties.setLinkExistingByEmail(false);
        oidcProperties.setRequireEmailVerified(false);
        OidcLoginException ex = Assertions.assertThrows(OidcLoginException.class, () ->
                service.resolveOrCreateByOidc("github", "sub-11", null, Boolean.FALSE));
        Assertions.assertEquals(OidcLoginErrorCode.EMAIL_MISSING, ex.getErrorCode());
    }
}
