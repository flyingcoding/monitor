package com.example.service;

import com.example.entity.dto.Account;
import com.example.mapper.AccountOidcBindingMapper;
import com.example.mapper.ApiTokenMapper;
import com.example.service.impl.AccountServiceImpl;
import com.example.utils.Const;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.Serializable;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link AccountServiceImpl#deleteSubAccount(int)} 的数据完整性测试。
 *
 * <p>测试以 JDK 动态代理记录 Mapper 调用，避免引入 Mockito 或数据库上下文。
 */
class AccountServiceImplSubAccountTest {

    private final Map<Integer, Account> accounts = new HashMap<>();
    private final AtomicInteger apiTokenDeleteCalls = new AtomicInteger();
    private final AtomicInteger oidcBindingDeleteCalls = new AtomicInteger();
    private AccountServiceImpl service;
    private boolean removed;

    /**
     * 初始化可记录删除操作的服务和 Mapper 替身。
     */
    @BeforeEach
    void setUp() {
        accounts.clear();
        apiTokenDeleteCalls.set(0);
        oidcBindingDeleteCalls.set(0);
        removed = false;
        service = new AccountServiceImpl() {
            @Override
            public Account getById(Serializable id) {
                return accounts.get(((Number) id).intValue());
            }

            @Override
            public boolean removeById(Serializable id) {
                removed = accounts.remove(((Number) id).intValue()) != null;
                return removed;
            }
        };

        ApiTokenMapper apiTokenMapper = (ApiTokenMapper) Proxy.newProxyInstance(
                ApiTokenMapper.class.getClassLoader(),
                new Class[]{ApiTokenMapper.class},
                (proxy, method, args) -> {
                    if ("delete".equals(method.getName())) {
                        apiTokenDeleteCalls.incrementAndGet();
                        return 1;
                    }
                    return defaultValue(method.getReturnType());
                });
        AccountOidcBindingMapper bindingMapper = (AccountOidcBindingMapper) Proxy.newProxyInstance(
                AccountOidcBindingMapper.class.getClassLoader(),
                new Class[]{AccountOidcBindingMapper.class},
                (proxy, method, args) -> {
                    if ("delete".equals(method.getName())) {
                        oidcBindingDeleteCalls.incrementAndGet();
                        return 1;
                    }
                    return defaultValue(method.getReturnType());
                });
        ReflectionTestUtils.setField(service, "apiTokenMapper", apiTokenMapper);
        ReflectionTestUtils.setField(service, "accountOidcBindingMapper", bindingMapper);
    }

    /**
     * 普通子账户删除时必须先回收所有关联认证凭据，再删除账号主记录。
     */
    @Test
    void deleteDefaultSubAccountShouldRemoveCredentialsAndAccount() {
        Account account = new Account();
        account.setId(10);
        account.setRole(Const.ROLE_DEFAULT);
        accounts.put(10, account);

        boolean result = service.deleteSubAccount(10);

        Assertions.assertTrue(result);
        Assertions.assertTrue(removed);
        Assertions.assertFalse(accounts.containsKey(10));
        Assertions.assertEquals(1, apiTokenDeleteCalls.get());
        Assertions.assertEquals(1, oidcBindingDeleteCalls.get());
    }

    /**
     * 非子账户不得通过该接口删除，且不能触发关联凭据清理。
     */
    @Test
    void deleteAdminAccountShouldBeRejectedWithoutDeletingCredentials() {
        Account account = new Account();
        account.setId(1);
        account.setRole(Const.ROLE_ADMIN);
        accounts.put(1, account);

        boolean result = service.deleteSubAccount(1);

        Assertions.assertFalse(result);
        Assertions.assertFalse(removed);
        Assertions.assertTrue(accounts.containsKey(1));
        Assertions.assertEquals(0, apiTokenDeleteCalls.get());
        Assertions.assertEquals(0, oidcBindingDeleteCalls.get());
    }

    /**
     * 为动态代理提供未关心返回类型的默认值。
     *
     * @param returnType Mapper 方法返回类型
     * @return 对应的零值
     */
    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        return null;
    }
}
