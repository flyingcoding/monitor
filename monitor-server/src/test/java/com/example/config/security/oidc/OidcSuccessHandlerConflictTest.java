package com.example.config.security.oidc;

import com.example.controller.OidcBindingController;
import com.example.entity.dto.Account;
import com.example.service.AccountOidcBindingService;
import com.example.service.AccountService;
import com.example.utils.JwtUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P2-2 OidcSuccessHandler 冲突分支测试。
 *
 * <p>覆盖 binding 流程下 {@link AccountOidcBindingService#bindIfFree} 返回 CONFLICT 时，
 * Handler 必须重定向到带 {@code oidc_bound=0} + {@code error=oidc_conflict} 的根路径，
 * 而不是误报 "绑定成功"。
 */
class OidcSuccessHandlerConflictTest {

    private OidcSuccessHandler handler;
    private Account stubAccount;
    private AccountOidcBindingService.BindingResult bindResult;

    @BeforeEach
    void setUp() {
        handler = new OidcSuccessHandler();
        stubAccount = new Account(7, "u7", "pwd", "u7@example.com", "user", "[]", new Date(), Boolean.TRUE);
        bindResult = AccountOidcBindingService.BindingResult.CREATED;

        AccountService accountService = (AccountService) Proxy.newProxyInstance(
                AccountService.class.getClassLoader(),
                new Class[]{AccountService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getById" -> stubAccount;
                    case "resolveOrCreateByOidc" -> stubAccount;
                    default -> null;
                });
        ReflectionTestUtils.setField(handler, "accountService", accountService);

        AccountOidcBindingService bindingService = (AccountOidcBindingService) Proxy.newProxyInstance(
                AccountOidcBindingService.class.getClassLoader(),
                new Class[]{AccountOidcBindingService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "bindIfFree" -> bindResult;
                    default -> null;
                });
        ReflectionTestUtils.setField(handler, "accountOidcBindingService", bindingService);

        JwtUtils jwtStub = new JwtUtils() {
            @Override
            public String createJwt(org.springframework.security.core.userdetails.UserDetails details,
                                    String username, int userId) {
                return "stub-jwt";
            }
            @Override
            public Date expireTime() {
                return new Date(System.currentTimeMillis() + 60_000);
            }
        };
        ReflectionTestUtils.setField(handler, "jwtUtils", jwtStub);
    }

    /**
     * 绑定流程 + bindIfFree=CONFLICT → 必须 302 到 oidc_bound=0 & error=oidc_conflict。
     */
    @Test
    void bindingConflictMustRedirectWithErrorNotSuccess() throws Exception {
        bindResult = AccountOidcBindingService.BindingResult.CONFLICT;

        StubSession session = new StubSession();
        session.setAttribute(OidcBindingController.SESSION_BINDING_ACCOUNT_ID, 7);
        StubResponse response = new StubResponse();
        Authentication auth = buildOidcAuth("github", "sub-other");

        handler.onAuthenticationSuccess(requestWithSession(session), response, auth);

        Assertions.assertNotNull(response.redirectedTo);
        Assertions.assertTrue(response.redirectedTo.contains("oidc_bound=0"),
                "P2-2：冲突时 oidc_bound 必须为 0，实际：" + response.redirectedTo);
        Assertions.assertTrue(response.redirectedTo.contains("error=oidc_conflict"),
                "P2-2：必须携带 error=oidc_conflict 让前端区分文案");
        Assertions.assertFalse(response.redirectedTo.contains("oidc_bound=1"),
                "P2-2：冲突时绝不能报告绑定成功");
    }

    /**
     * 绑定流程 + bindIfFree=CREATED → 必须 302 到 oidc_bound=1。
     */
    @Test
    void bindingCreatedRedirectsSuccess() throws Exception {
        bindResult = AccountOidcBindingService.BindingResult.CREATED;

        StubSession session = new StubSession();
        session.setAttribute(OidcBindingController.SESSION_BINDING_ACCOUNT_ID, 7);
        StubResponse response = new StubResponse();
        Authentication auth = buildOidcAuth("github", "sub-new");

        handler.onAuthenticationSuccess(requestWithSession(session), response, auth);

        Assertions.assertNotNull(response.redirectedTo);
        Assertions.assertTrue(response.redirectedTo.contains("oidc_bound=1"),
                "成功创建绑定时必须报告 oidc_bound=1，实际：" + response.redirectedTo);
    }

    /**
     * 登录流程（非绑定）+ bindIfFree=CONFLICT → 必须 302 到 error=oidc_conflict，不能签发 JWT。
     */
    @Test
    void loginConflictMustNotIssueJwt() throws Exception {
        bindResult = AccountOidcBindingService.BindingResult.CONFLICT;

        StubResponse response = new StubResponse();
        Authentication auth = buildOidcAuth("github", "sub-mismatch");

        handler.onAuthenticationSuccess(requestWithSession(null), response, auth);

        Assertions.assertNotNull(response.redirectedTo);
        Assertions.assertTrue(response.redirectedTo.contains("oidc_error=oidc_conflict"),
                "P2-2：登录流程命中冲突也必须报错，实际：" + response.redirectedTo);
        Assertions.assertFalse(response.redirectedTo.contains("oidc_token="),
                "P2-2：冲突时绝不能签发 JWT");
    }

    private static OAuth2AuthenticationToken buildOidcAuth(String provider, String subject) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", subject);
        claims.put("email", subject + "@example.com");
        claims.put("email_verified", true);
        OidcIdToken idToken = new OidcIdToken("token-value",
                Instant.now(), Instant.now().plusSeconds(300), claims);
        OidcUser oidcUser = new DefaultOidcUser(List.of(), idToken);
        return new OAuth2AuthenticationToken(oidcUser, List.of(), provider);
    }

    private static HttpServletRequest requestWithSession(HttpSession session) {
        Map<String, Object> attrs = new HashMap<>();
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getSession" -> {
                        if (args != null && args.length == 1 && Boolean.FALSE.equals(args[0])) {
                            yield session;
                        }
                        yield session;
                    }
                    case "setAttribute" -> {
                        attrs.put((String) args[0], args[1]);
                        yield null;
                    }
                    case "getAttribute" -> attrs.get((String) args[0]);
                    default -> null;
                });
    }

    /**
     * 极简 HttpSession 桩，仅支撑 getAttribute / removeAttribute。
     */
    static class StubSession implements HttpSession {
        final Map<String, Object> attributes = new HashMap<>();

        @Override public void setAttribute(String name, Object value) { attributes.put(name, value); }
        @Override public Object getAttribute(String name) { return attributes.get(name); }
        @Override public void removeAttribute(String name) { attributes.remove(name); }
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
     * 极简 HttpServletResponse：仅记录 sendRedirect 的 location。
     */
    static class StubResponse implements HttpServletResponse {
        String redirectedTo;

        @Override public void sendRedirect(String location) { this.redirectedTo = location; }

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
