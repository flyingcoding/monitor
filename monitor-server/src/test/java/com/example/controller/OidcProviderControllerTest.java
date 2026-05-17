package com.example.controller;

import com.example.entity.RestBean;
import com.example.entity.vo.request.OidcProviderCreateVO;
import com.example.entity.vo.request.OidcProviderUpdateVO;
import com.example.entity.vo.response.OidcProviderVO;
import com.example.service.OidcProviderService;
import com.example.service.PermissionService;
import com.example.utils.Const;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link OidcProviderController} 单元测试，覆盖 admin CRUD 的
 * "API Token 不能管理 OIDC Provider" 一致性 guard。
 *
 * <p>风格沿用 {@code ApiTokenControllerTest}：JDK 动态代理 stub Service。
 */
class OidcProviderControllerTest {

    private OidcProviderController controller;
    private final AtomicBoolean serviceCalled = new AtomicBoolean(false);

    @BeforeEach
    void setUp() {
        serviceCalled.set(false);
        controller = new OidcProviderController();
        OidcProviderService stub = (OidcProviderService) Proxy.newProxyInstance(
                OidcProviderService.class.getClassLoader(),
                new Class[]{OidcProviderService.class},
                (proxy, method, args) -> {
                    serviceCalled.set(true);
                    return switch (method.getName()) {
                        case "listAll" -> List.<OidcProviderVO>of();
                        case "delete" -> true;
                        case "create", "update" -> new OidcProviderVO();
                        default -> null;
                    };
                });
        ReflectionTestUtils.setField(controller, "oidcProviderService", stub);

        PermissionService permission = new PermissionService() {
            @Override
            public boolean isAdmin(String role) {
                return "admin".equals(role);
            }
        };
        ReflectionTestUtils.setField(controller, "permissionService", permission);
    }

    private static HttpServletRequest requestWithAuthMethod(String method) {
        Map<String, Object> attrs = new HashMap<>();
        if (method != null) {
            attrs.put(Const.ATTR_AUTH_METHOD, method);
        }
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class[]{HttpServletRequest.class},
                (proxy, m, args) -> {
                    if ("getAttribute".equals(m.getName())) {
                        return attrs.get((String) args[0]);
                    }
                    return null;
                });
    }

    @Test
    void listWithApiTokenAuthShouldBeForbidden() {
        RestBean<List<OidcProviderVO>> result = controller.list(
                requestWithAuthMethod(Const.AUTH_METHOD_API_TOKEN), "admin");
        Assertions.assertEquals(403, result.code());
        Assertions.assertFalse(serviceCalled.get(), "service 不应被调用");
        Assertions.assertTrue(result.message().contains("API Token"));
    }

    @Test
    void createWithApiTokenAuthShouldBeForbidden() {
        OidcProviderCreateVO vo = new OidcProviderCreateVO();
        RestBean<OidcProviderVO> result = controller.create(
                requestWithAuthMethod(Const.AUTH_METHOD_API_TOKEN), vo, "admin");
        Assertions.assertEquals(403, result.code());
        Assertions.assertFalse(serviceCalled.get());
    }

    @Test
    void updateWithApiTokenAuthShouldBeForbidden() {
        OidcProviderUpdateVO vo = new OidcProviderUpdateVO();
        RestBean<OidcProviderVO> result = controller.update(
                requestWithAuthMethod(Const.AUTH_METHOD_API_TOKEN), 1L, vo, "admin");
        Assertions.assertEquals(403, result.code());
        Assertions.assertFalse(serviceCalled.get());
    }

    @Test
    void deleteWithApiTokenAuthShouldBeForbidden() {
        RestBean<Void> result = controller.delete(
                requestWithAuthMethod(Const.AUTH_METHOD_API_TOKEN), 1L, "admin");
        Assertions.assertEquals(403, result.code());
        Assertions.assertFalse(serviceCalled.get());
    }

    @Test
    void jwtAdminBypassesGuardAndDelegatesToService() {
        HttpServletRequest req = requestWithAuthMethod(Const.AUTH_METHOD_JWT);
        Assertions.assertEquals(200, controller.list(req, "admin").code());
        Assertions.assertTrue(serviceCalled.get());

        serviceCalled.set(false);
        Assertions.assertEquals(200, controller.delete(req, 1L, "admin").code());
        Assertions.assertTrue(serviceCalled.get());
    }

    @Test
    void jwtNonAdminReturnsNoPermissionNot403() {
        HttpServletRequest req = requestWithAuthMethod(Const.AUTH_METHOD_JWT);
        RestBean<List<OidcProviderVO>> result = controller.list(req, "user");
        // RestBean.noPermission() returns code 401 (matching existing convention).
        Assertions.assertNotEquals(403, result.code());
        Assertions.assertFalse(serviceCalled.get(), "非管理员不应触达 service");
    }
}
