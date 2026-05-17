package com.example.service;

import com.example.entity.dto.Account;
import com.example.entity.vo.response.ClientPreviewVO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.List;

class PermissionServiceTest {

    /**
     * 验证管理员角色判断兼容 ROLE_ 前缀。
     */
    @Test
    void isAdminShouldSupportRolePrefix() {
        PermissionService permissionService = new PermissionService();
        Assertions.assertTrue(permissionService.isAdmin("ROLE_admin"));
        Assertions.assertTrue(permissionService.isAdmin("admin"));
        Assertions.assertFalse(permissionService.isAdmin("ROLE_user"));
        Assertions.assertFalse(permissionService.isAdmin(null));
    }

    /**
     * 验证普通用户客户端访问权限判断与过滤逻辑。
     */
    @Test
    void permissionCheckShouldUseAccountClientList() {
        PermissionService permissionService = new PermissionService();
        Account account = new Account(1, "u1", "p1", "u1@test.com", "user", "[1,3]", null, Boolean.TRUE);

        AccountService accountService = (AccountService) Proxy.newProxyInstance(
                AccountService.class.getClassLoader(),
                new Class[]{AccountService.class},
                (proxy, method, args) -> {
                    if ("getById".equals(method.getName())) {
                        return account;
                    }
                    if ("toString".equals(method.getName())) {
                        return "AccountServiceTestStub";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    throw new UnsupportedOperationException("测试桩未实现方法: " + method.getName());
                }
        );
        ReflectionTestUtils.setField(permissionService, "accountService", accountService);

        Assertions.assertTrue(permissionService.canAccessClient(1, "ROLE_user", 1));
        Assertions.assertFalse(permissionService.canAccessClient(1, "ROLE_user", 2));

        ClientPreviewVO a = new ClientPreviewVO();
        a.setId(1);
        ClientPreviewVO b = new ClientPreviewVO();
        b.setId(2);
        List<ClientPreviewVO> filtered = permissionService.filterClientsByPermission(List.of(a, b), 1, "ROLE_user");
        Assertions.assertEquals(1, filtered.size());
        Assertions.assertEquals(1, filtered.get(0).getId());
    }
}
