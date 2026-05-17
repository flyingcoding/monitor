package com.example.controller;

import com.example.entity.RestBean;
import com.example.entity.vo.response.OidcBindingVO;
import com.example.service.AccountOidcBindingService;
import com.example.service.OidcBindingIntentService;
import com.example.utils.Const;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link OidcBindingController} 单元测试 — P2-2 修复覆盖：
 *
 * <ul>
 *   <li>{@code POST /api/oidc/bindings/intent} 走 JWT，签发的 intent token 关联 accountId；</li>
 *   <li>{@code GET /api/oidc/bindings/start/{provider}?intent=...} 在 intent 校验通过后把 accountId
 *       写入 HTTP session 并 302 到 {@code /oauth2/authorization/{provider}}；</li>
 *   <li>无效 / 过期的 intent token 不写 session，重定向回登录页携带 oidc_error；</li>
 *   <li>消费过的 intent token 第二次使用必须失败（防止重放）。</li>
 * </ul>
 */
class OidcBindingControllerTest {

    private OidcBindingController controller;
    private final Map<String, Integer> redisStore = new HashMap<>();
    private final AtomicInteger tokenCounter = new AtomicInteger(0);

    @BeforeEach
    void setUp() {
        redisStore.clear();
        tokenCounter.set(0);
        controller = new OidcBindingController();

        AccountOidcBindingService bindingService = (AccountOidcBindingService) Proxy.newProxyInstance(
                AccountOidcBindingService.class.getClassLoader(),
                new Class[]{AccountOidcBindingService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "listByAccount" -> List.<OidcBindingVO>of();
                    default -> null;
                });
        ReflectionTestUtils.setField(controller, "accountOidcBindingService", bindingService);

        OidcBindingIntentService intentService = (OidcBindingIntentService) Proxy.newProxyInstance(
                OidcBindingIntentService.class.getClassLoader(),
                new Class[]{OidcBindingIntentService.class},
                (proxy, method, args) -> {
                    if ("issue".equals(method.getName())) {
                        String token = "intent-" + tokenCounter.incrementAndGet();
                        redisStore.put(token, (Integer) args[0]);
                        return token;
                    }
                    if ("consume".equals(method.getName())) {
                        Integer aid = redisStore.remove((String) args[0]);
                        return aid == null ? Optional.empty() : Optional.of(aid);
                    }
                    return null;
                });
        ReflectionTestUtils.setField(controller, "oidcBindingIntentService", intentService);
    }

    @Test
    void issueIntentReturnsTokenAssociatedWithAccountId() {
        RestBean<Map<String, Object>> result = controller.issueIntent(requestWithAuthMethod(Const.AUTH_METHOD_JWT), 42);
        Assertions.assertEquals(200, result.code());
        String token = (String) result.data().get("intentToken");
        Assertions.assertNotNull(token);
        Assertions.assertEquals(42, redisStore.get(token),
                "P2-2：intent token 必须与签发账号绑定");
    }

    /**
     * 防御性补充：API Token 鉴权不允许签发绑定意图 token，防止 readwrite token 自我升权绑定 IdP。
     */
    @Test
    void issueIntentWithApiTokenAuthShouldBeForbidden() {
        RestBean<Map<String, Object>> result = controller.issueIntent(
                requestWithAuthMethod(Const.AUTH_METHOD_API_TOKEN), 42);
        Assertions.assertEquals(403, result.code());
        Assertions.assertTrue(redisStore.isEmpty(), "禁止流程下不应签发 token");
    }

    @Test
    void startBindingWithValidIntentStoresAccountIdInSessionAndRedirects() throws Exception {
        controller.issueIntent(requestWithAuthMethod(Const.AUTH_METHOD_JWT), 42);
        String token = redisStore.keySet().iterator().next();

        StubSession session = new StubSession();
        StubResponse response = new StubResponse();
        controller.startBinding("github", token, requestWithSession(session), response);

        Assertions.assertEquals("/oauth2/authorization/github", response.redirectedTo,
                "P2-2：合法 intent 应 302 到 OAuth 授权端点");
        Assertions.assertEquals(42, session.attributes.get(OidcBindingController.SESSION_BINDING_ACCOUNT_ID),
                "P2-2：accountId 必须被写入 session，SuccessHandler 据此判断绑定 vs 登录");
        Assertions.assertFalse(redisStore.containsKey(token),
                "P2-2：intent 必须被消费删除，防止重放");
    }

    @Test
    void startBindingWithInvalidIntentRedirectsToErrorAndDoesNotTouchSession() throws Exception {
        StubSession session = new StubSession();
        StubResponse response = new StubResponse();
        controller.startBinding("github", "non-existent-intent", requestWithSession(session), response);

        Assertions.assertNotNull(response.redirectedTo);
        Assertions.assertTrue(response.redirectedTo.contains("oidc_error=invalid_intent"),
                "P2-2：无效 intent 必须重定向到登录错误页，实际：" + response.redirectedTo);
        Assertions.assertNull(session.attributes.get(OidcBindingController.SESSION_BINDING_ACCOUNT_ID),
                "P2-2：无效 intent 不得写入 session");
    }

    @Test
    void consumedIntentCannotBeReused() throws Exception {
        controller.issueIntent(requestWithAuthMethod(Const.AUTH_METHOD_JWT), 99);
        String token = redisStore.keySet().iterator().next();

        StubSession session1 = new StubSession();
        controller.startBinding("github", token, requestWithSession(session1), new StubResponse());
        Assertions.assertEquals(99, session1.attributes.get(OidcBindingController.SESSION_BINDING_ACCOUNT_ID));

        // 第二次使用同一 token：应被拒
        StubSession session2 = new StubSession();
        StubResponse resp2 = new StubResponse();
        controller.startBinding("github", token, requestWithSession(session2), resp2);
        Assertions.assertNull(session2.attributes.get(OidcBindingController.SESSION_BINDING_ACCOUNT_ID),
                "P2-2：消费后的 intent 不能再次被使用");
        Assertions.assertTrue(resp2.redirectedTo.contains("oidc_error=invalid_intent"));
    }

    @Test
    void intentForDifferentAccountIsolated() {
        controller.issueIntent(requestWithAuthMethod(Const.AUTH_METHOD_JWT), 1);
        controller.issueIntent(requestWithAuthMethod(Const.AUTH_METHOD_JWT), 2);
        Assertions.assertEquals(2, redisStore.size());
        // 两个 token 分别对应不同账号
        Assertions.assertTrue(redisStore.containsValue(1));
        Assertions.assertTrue(redisStore.containsValue(2));
    }

    private static HttpServletRequest requestWithSession(StubSession session) {
        AtomicReference<StubSession> ref = new AtomicReference<>(session);
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getSession" -> ref.get();
                    default -> null;
                });
    }

    /**
     * 仅承载 {@code ATTR_AUTH_METHOD} 用以驱动 {@code issueIntent} 的 API Token 守卫。
     */
    private static HttpServletRequest requestWithAuthMethod(String authMethod) {
        Map<String, Object> attrs = new HashMap<>();
        if (authMethod != null) {
            attrs.put(Const.ATTR_AUTH_METHOD, authMethod);
        }
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class[]{HttpServletRequest.class},
                (proxy, method, args) -> {
                    if ("getAttribute".equals(method.getName())) {
                        return attrs.get((String) args[0]);
                    }
                    return null;
                });
    }
    /**
     * 最小化 HttpSession 桩。
     */
    static class StubSession implements HttpSession {
        final Map<String, Object> attributes = new HashMap<>();

        @Override
        public void setAttribute(String name, Object value) {
            attributes.put(name, value);
        }

        @Override
        public Object getAttribute(String name) {
            return attributes.get(name);
        }

        @Override
        public void removeAttribute(String name) {
            attributes.remove(name);
        }

        @Override public long getCreationTime() { return 0; }
        @Override public String getId() { return "stub"; }
        @Override public long getLastAccessedTime() { return 0; }
        @Override public jakarta.servlet.ServletContext getServletContext() { return null; }
        @Override public void setMaxInactiveInterval(int interval) {}
        @Override public int getMaxInactiveInterval() { return 0; }
        @Override public java.util.Enumeration<String> getAttributeNames() {
            return java.util.Collections.enumeration(attributes.keySet());
        }
        @Override public void invalidate() {}
        @Override public boolean isNew() { return true; }
    }

    /**
     * 最小化 HttpServletResponse：仅捕获 sendRedirect 的 location。
     */
    static class StubResponse implements HttpServletResponse {
        String redirectedTo;

        @Override public void sendRedirect(String location) {
            this.redirectedTo = location;
        }

        // --- 其他全部空实现 ---
        @Override public void addCookie(jakarta.servlet.http.Cookie cookie) {}
        @Override public boolean containsHeader(String name) { return false; }
        @Override public String encodeURL(String url) { return url; }
        @Override public String encodeRedirectURL(String url) { return url; }
        @Override public void sendError(int sc, String msg) {}
        @Override public void sendError(int sc) {}
        @Override public void setDateHeader(String name, long date) {}
        @Override public void addDateHeader(String name, long date) {}
        @Override public void setHeader(String name, String value) {}
        @Override public void addHeader(String name, String value) {}
        @Override public void setIntHeader(String name, int value) {}
        @Override public void addIntHeader(String name, int value) {}
        @Override public void setStatus(int sc) {}
        @Override public int getStatus() { return 0; }
        @Override public String getHeader(String name) { return null; }
        @Override public java.util.Collection<String> getHeaders(String name) { return java.util.List.of(); }
        @Override public java.util.Collection<String> getHeaderNames() { return java.util.List.of(); }
        @Override public String getCharacterEncoding() { return "utf-8"; }
        @Override public String getContentType() { return null; }
        @Override public jakarta.servlet.ServletOutputStream getOutputStream() { return null; }
        @Override public java.io.PrintWriter getWriter() { return new java.io.PrintWriter(new java.io.StringWriter()); }
        @Override public void setCharacterEncoding(String charset) {}
        @Override public void setContentLength(int len) {}
        @Override public void setContentLengthLong(long len) {}
        @Override public void setContentType(String type) {}
        @Override public void setBufferSize(int size) {}
        @Override public int getBufferSize() { return 0; }
        @Override public void flushBuffer() {}
        @Override public void resetBuffer() {}
        @Override public boolean isCommitted() { return false; }
        @Override public void reset() {}
        @Override public void setLocale(java.util.Locale loc) {}
        @Override public java.util.Locale getLocale() { return java.util.Locale.ROOT; }
    }
}
