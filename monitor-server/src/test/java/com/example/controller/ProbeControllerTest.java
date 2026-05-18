package com.example.controller;

import com.example.entity.RestBean;
import com.example.entity.vo.request.ProbeTaskCreateVO;
import com.example.entity.vo.request.ProbeTaskUpdateVO;
import com.example.entity.vo.response.ProbeHistoryVO;
import com.example.entity.vo.response.ProbeTaskVO;
import com.example.service.PermissionService;
import com.example.service.ProbeService;
import com.example.utils.Const;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link ProbeController} 单元测试，覆盖 admin 鉴权 + API Token guard + 分页参数校验。
 */
class ProbeControllerTest {

    private ProbeController controller;
    private final AtomicBoolean serviceCalled = new AtomicBoolean(false);

    @BeforeEach
    void setUp() {
        serviceCalled.set(false);
        controller = new ProbeController();

        ProbeService stub = (ProbeService) Proxy.newProxyInstance(
                ProbeService.class.getClassLoader(),
                new Class[]{ProbeService.class},
                (proxy, method, args) -> {
                    serviceCalled.set(true);
                    return switch (method.getName()) {
                        case "listAll" -> List.<ProbeTaskVO>of();
                        case "delete" -> true;
                        case "create", "update" -> new ProbeTaskVO();
                        case "listHistory" -> {
                            Page<ProbeHistoryVO> p = new Page<>(1, 20);
                            p.setRecords(List.of());
                            p.setTotal(0);
                            yield p;
                        }
                        default -> null;
                    };
                });
        ReflectionTestUtils.setField(controller, "probeService", stub);

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
        RestBean<List<ProbeTaskVO>> result = controller.list(
                requestWithAuthMethod(Const.AUTH_METHOD_API_TOKEN), "admin");
        Assertions.assertEquals(403, result.code());
        Assertions.assertFalse(serviceCalled.get());
        Assertions.assertTrue(result.message().contains("API Token"));
    }

    @Test
    void createWithApiTokenAuthShouldBeForbidden() {
        ProbeTaskCreateVO vo = new ProbeTaskCreateVO();
        RestBean<ProbeTaskVO> result = controller.create(
                requestWithAuthMethod(Const.AUTH_METHOD_API_TOKEN), vo, "admin");
        Assertions.assertEquals(403, result.code());
        Assertions.assertFalse(serviceCalled.get());
    }

    @Test
    void updateWithApiTokenAuthShouldBeForbidden() {
        ProbeTaskUpdateVO vo = new ProbeTaskUpdateVO();
        RestBean<ProbeTaskVO> result = controller.update(
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
    void historyWithApiTokenAuthShouldBeForbidden() {
        RestBean<ProbeController.ProbeHistoryPageVO> result = controller.history(
                requestWithAuthMethod(Const.AUTH_METHOD_API_TOKEN), 1L, 1, 20, "admin");
        Assertions.assertEquals(403, result.code());
        Assertions.assertFalse(serviceCalled.get());
    }

    @Test
    void jwtAdminCanListAndDelete() {
        HttpServletRequest req = requestWithAuthMethod(Const.AUTH_METHOD_JWT);
        Assertions.assertEquals(200, controller.list(req, "admin").code());
        Assertions.assertTrue(serviceCalled.get());

        serviceCalled.set(false);
        Assertions.assertEquals(200, controller.delete(req, 1L, "admin").code());
        Assertions.assertTrue(serviceCalled.get());
    }

    @Test
    void jwtNonAdminReturnsNoPermission() {
        HttpServletRequest req = requestWithAuthMethod(Const.AUTH_METHOD_JWT);
        RestBean<List<ProbeTaskVO>> result = controller.list(req, "user");
        Assertions.assertEquals(401, result.code());
        Assertions.assertFalse(serviceCalled.get(), "非管理员不应触达 service");
    }

    @Test
    void historyShouldRejectInvalidPagination() {
        HttpServletRequest req = requestWithAuthMethod(Const.AUTH_METHOD_JWT);
        Assertions.assertThrows(ResponseStatusException.class,
                () -> controller.history(req, 1L, 0, 20, "admin"));
        Assertions.assertThrows(ResponseStatusException.class,
                () -> controller.history(req, 1L, 1, 0, "admin"));
        Assertions.assertThrows(ResponseStatusException.class,
                () -> controller.history(req, 1L, 1, 500, "admin"));
    }

    @Test
    void historyShouldReturnPageVO() {
        HttpServletRequest req = requestWithAuthMethod(Const.AUTH_METHOD_JWT);
        RestBean<ProbeController.ProbeHistoryPageVO> result = controller.history(req, 1L, 1, 20, "admin");
        Assertions.assertEquals(200, result.code());
        Assertions.assertNotNull(result.data());
        Assertions.assertEquals(0, result.data().getTotal());
    }

    @Test
    void deleteMissingShouldReturn404() {
        // 用专门 stub 让 delete 返回 false
        ProbeService stub = (ProbeService) Proxy.newProxyInstance(
                ProbeService.class.getClassLoader(),
                new Class[]{ProbeService.class},
                (proxy, method, args) -> {
                    if ("delete".equals(method.getName())) {
                        return false;
                    }
                    return null;
                });
        ReflectionTestUtils.setField(controller, "probeService", stub);
        HttpServletRequest req = requestWithAuthMethod(Const.AUTH_METHOD_JWT);
        RestBean<Void> result = controller.delete(req, 999L, "admin");
        Assertions.assertEquals(404, result.code());
    }
}
