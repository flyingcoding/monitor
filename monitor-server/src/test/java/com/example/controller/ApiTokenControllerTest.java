package com.example.controller;

import com.example.entity.RestBean;
import com.example.entity.vo.request.ApiTokenCreateVO;
import com.example.entity.vo.response.ApiTokenCreatedVO;
import com.example.entity.vo.response.ApiTokenVO;
import com.example.service.ApiTokenService;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link ApiTokenController} 单元测试，覆盖 P2-1 修复：
 * 所有写入/列表/旋转/删除接口都拒绝以 API Token 鉴权的请求，强制使用 JWT。
 *
 * <p>风格沿用项目惯例（JDK 动态代理 stub Service）。
 */
class ApiTokenControllerTest {

    private ApiTokenController controller;
    private final AtomicBoolean serviceCalled = new AtomicBoolean(false);

    @BeforeEach
    void setUp() {
        serviceCalled.set(false);
        controller = new ApiTokenController();
        ApiTokenService stub = (ApiTokenService) Proxy.newProxyInstance(
                ApiTokenService.class.getClassLoader(),
                new Class[]{ApiTokenService.class},
                (proxy, method, args) -> {
                    serviceCalled.set(true);
                    return switch (method.getName()) {
                        case "list" -> List.<ApiTokenVO>of();
                        case "delete" -> true;
                        case "create", "rotate" -> {
                            ApiTokenCreatedVO created = new ApiTokenCreatedVO();
                            created.setToken("mtk_dummy0000000000000000000000000000");
                            ApiTokenVO meta = new ApiTokenVO();
                            meta.setId(1L);
                            created.setMeta(meta);
                            yield method.getName().equals("rotate") ? Optional.of(created) : created;
                        }
                        default -> null;
                    };
                });
        ReflectionTestUtils.setField(controller, "apiTokenService", stub);
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

    /**
     * P2-1：API Token 鉴权调用 create 必须返回 403。
     */
    @Test
    void createWithApiTokenAuthShouldBeForbidden() {
        ApiTokenCreateVO vo = new ApiTokenCreateVO();
        vo.setName("t");
        vo.setScope("readonly");
        RestBean<ApiTokenCreatedVO> result = controller.create(
                requestWithAuthMethod(Const.AUTH_METHOD_API_TOKEN), 7, vo);
        Assertions.assertEquals(403, result.code());
        Assertions.assertFalse(serviceCalled.get(), "service 不应被调用");
        Assertions.assertTrue(result.message().contains("API Token"));
    }

    /**
     * P2-1：API Token 鉴权调用 list 必须返回 403。
     */
    @Test
    void listWithApiTokenAuthShouldBeForbidden() {
        RestBean<List<ApiTokenVO>> result = controller.list(
                requestWithAuthMethod(Const.AUTH_METHOD_API_TOKEN), 7);
        Assertions.assertEquals(403, result.code());
        Assertions.assertFalse(serviceCalled.get());
        Assertions.assertTrue(result.message().contains("API Token"));
    }

    /**
     * P2-1：API Token 鉴权调用 delete 必须返回 403。
     */
    @Test
    void deleteWithApiTokenAuthShouldBeForbidden() {
        RestBean<Void> result = controller.delete(
                requestWithAuthMethod(Const.AUTH_METHOD_API_TOKEN), 7, 1L);
        Assertions.assertEquals(403, result.code());
        Assertions.assertFalse(serviceCalled.get());
    }

    /**
     * P2-1：API Token 鉴权调用 rotate 必须返回 403。
     */
    @Test
    void rotateWithApiTokenAuthShouldBeForbidden() {
        RestBean<ApiTokenCreatedVO> result = controller.rotate(
                requestWithAuthMethod(Const.AUTH_METHOD_API_TOKEN), 7, 1L);
        Assertions.assertEquals(403, result.code());
        Assertions.assertFalse(serviceCalled.get());
    }

    /**
     * 反向验证：JWT 鉴权时所有接口正常工作。
     */
    @Test
    void jwtAuthShouldBypassGuard() {
        HttpServletRequest req = requestWithAuthMethod(Const.AUTH_METHOD_JWT);
        Assertions.assertEquals(200, controller.list(req, 7).code());
        Assertions.assertTrue(serviceCalled.get(), "JWT 鉴权下 service 应被调用");

        serviceCalled.set(false);
        Assertions.assertEquals(200, controller.delete(req, 7, 1L).code());
        Assertions.assertTrue(serviceCalled.get());
    }
}
