package com.example.controller;

import com.example.entity.RestBean;
import com.example.entity.vo.request.CreateSubAccountVO;
import com.example.entity.vo.response.SubAccountVO;
import com.example.service.AccountService;
import com.example.service.PermissionService;
import com.example.utils.Const;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link UserController} 子账户管理权限回归测试。
 */
class UserControllerTest {

    private UserController controller;
    private final AtomicInteger serviceCalls = new AtomicInteger();

    /**
     * 装配记录调用次数的 AccountService 替身与真实角色判断服务。
     */
    @BeforeEach
    void setUp() {
        serviceCalls.set(0);
        controller = new UserController();
        AccountService service = (AccountService) Proxy.newProxyInstance(
                AccountService.class.getClassLoader(),
                new Class[]{AccountService.class},
                (proxy, method, args) -> {
                    serviceCalls.incrementAndGet();
                    return switch (method.getName()) {
                        case "deleteSubAccount" -> true;
                        case "listSubAccount" -> List.<SubAccountVO>of();
                        default -> null;
                    };
                });
        ReflectionTestUtils.setField(controller, "service", service);
        ReflectionTestUtils.setField(controller, "permissionService", new PermissionService());
    }

    /**
     * 普通 JWT 用户不能绕过前端管理员入口管理任何子账户资源。
     */
    @Test
    void nonAdminShouldNotManageSubAccounts() {
        HttpServletRequest request = requestWithAuthMethod(Const.AUTH_METHOD_JWT);

        RestBean<Void> create = controller.createSubAccount(request, createRequest(), "ROLE_user");
        RestBean<Void> delete = controller.deleteSubAccount(request, 2, 1, "ROLE_user");
        RestBean<List<SubAccountVO>> list = controller.subAccountList(request, "ROLE_user");

        Assertions.assertEquals(401, create.code());
        Assertions.assertEquals(401, delete.code());
        Assertions.assertEquals(401, list.code());
        Assertions.assertEquals(0, serviceCalls.get());
    }

    /**
     * 即使 API Token 所属账号是管理员，也不得通过 Token 管理账户体系。
     */
    @Test
    void apiTokenShouldNotManageSubAccounts() {
        HttpServletRequest request = requestWithAuthMethod(Const.AUTH_METHOD_API_TOKEN);

        RestBean<Void> create = controller.createSubAccount(request, createRequest(), "ROLE_admin");
        RestBean<Void> delete = controller.deleteSubAccount(request, 2, 1, "ROLE_admin");
        RestBean<List<SubAccountVO>> list = controller.subAccountList(request, "ROLE_admin");

        Assertions.assertEquals(403, create.code());
        Assertions.assertEquals(403, delete.code());
        Assertions.assertEquals(403, list.code());
        Assertions.assertEquals(0, serviceCalls.get());
    }

    /**
     * JWT 管理员能够调用子账户管理服务。
     */
    @Test
    void jwtAdminShouldManageSubAccounts() {
        HttpServletRequest request = requestWithAuthMethod(Const.AUTH_METHOD_JWT);

        Assertions.assertEquals(200,
                controller.createSubAccount(request, createRequest(), "ROLE_admin").code());
        Assertions.assertEquals(200,
                controller.deleteSubAccount(request, 2, 1, "ROLE_admin").code());
        Assertions.assertEquals(200,
                controller.subAccountList(request, "ROLE_admin").code());
        Assertions.assertEquals(3, serviceCalls.get());
    }

    /**
     * 构造可供 controller 使用的鉴权方式请求替身。
     *
     * @param authMethod 鉴权方式
     * @return HTTP 请求替身
     */
    private static HttpServletRequest requestWithAuthMethod(String authMethod) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class[]{HttpServletRequest.class},
                (proxy, method, args) -> "getAttribute".equals(method.getName())
                        && Const.ATTR_AUTH_METHOD.equals(args[0]) ? authMethod : null);
    }

    /**
     * 构造最小合法的子账户创建请求。
     *
     * @return 创建请求
     */
    private static CreateSubAccountVO createRequest() {
        CreateSubAccountVO vo = new CreateSubAccountVO();
        vo.setUsername("sub-user");
        vo.setEmail("sub@example.com");
        vo.setPassword("Password123");
        vo.setClients(List.of());
        return vo;
    }
}
