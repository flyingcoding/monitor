package com.example.filter;

import com.example.entity.dto.Account;
import com.example.entity.dto.ApiToken;
import com.example.service.AccountService;
import com.example.service.ApiTokenService;
import com.example.utils.Const;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ApiTokenFilter 单元测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>SecurityContext 已认证时跳过；</li>
 *   <li>非 mtk_ 前缀的 Authorization 直接放行（不消费）；</li>
 *   <li>有效 token + 启用账号 → 写入 SecurityContext + 请求属性；</li>
 *   <li>无效 token → 401 RestBean；</li>
 *   <li>禁用账号 → 401 RestBean；</li>
 *   <li>readonly token + POST → 403 RestBean；</li>
 *   <li>readonly token + GET → 放行；</li>
 *   <li>X-Api-Token header 优先于 Authorization。</li>
 * </ul>
 *
 * <p>遵循项目惯例，使用 JDK 动态代理替代 Mockito。
 */
class ApiTokenFilterTest {

    private ApiTokenFilter filter;
    private final Map<String, ApiToken> tokensByHash = new HashMap<>();
    private final Map<Integer, Account> accountsById = new HashMap<>();
    private final AtomicInteger recordUsageCalls = new AtomicInteger(0);

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        tokensByHash.clear();
        accountsById.clear();
        recordUsageCalls.set(0);

        filter = new ApiTokenFilter();

        ApiTokenService tokenService = (ApiTokenService) Proxy.newProxyInstance(
                ApiTokenService.class.getClassLoader(),
                new Class[]{ApiTokenService.class},
                (proxy, method, args) -> {
                    if ("validateAndResolve".equals(method.getName())) {
                        String raw = (String) args[0];
                        // 极简 stub：用明文 token 字符串本身作为 key
                        if (raw == null) return Optional.empty();
                        ApiToken hit = tokensByHash.get(raw);
                        if (hit == null) return Optional.empty();
                        if (hit.getExpiresAt() != null && hit.getExpiresAt().before(new Date())) {
                            return Optional.empty();
                        }
                        return Optional.of(hit);
                    }
                    if ("recordUsage".equals(method.getName())) {
                        recordUsageCalls.incrementAndGet();
                        return null;
                    }
                    return null;
                });
        ReflectionTestUtils.setField(filter, "apiTokenService", tokenService);

        AccountService accountService = (AccountService) Proxy.newProxyInstance(
                AccountService.class.getClassLoader(),
                new Class[]{AccountService.class},
                (proxy, method, args) -> {
                    if ("getById".equals(method.getName())) {
                        int id = ((Number) args[0]).intValue();
                        return accountsById.get(id);
                    }
                    return null;
                });
        ReflectionTestUtils.setField(filter, "accountService", accountService);
    }

    /**
     * SecurityContext 已认证时（JWT 已校验）直接跳过过滤器，不消费 Authorization 头。
     */
    @Test
    void shouldSkipWhenAlreadyAuthenticated() throws Exception {
        Authentication existing = new UsernamePasswordAuthenticationToken(
                User.withUsername("alice").password("x").roles("user").build(),
                null, new ArrayList<>());
        SecurityContextHolder.getContext().setAuthentication(existing);

        StubHttpRequest req = new StubHttpRequest("GET", "/api/tokens");
        // 故意带一个无效 mtk_ token，验证未消费
        req.setHeader("Authorization", "Bearer mtk_invalid");
        StubHttpResponse resp = new StubHttpResponse();
        AtomicBoolean chained = new AtomicBoolean(false);
        FilterChain chain = (request, response) -> chained.set(true);

        filter.doFilterInternal(req, resp, chain);

        Assertions.assertTrue(chained.get(), "应放行到下一个 filter");
        Assertions.assertEquals(0, recordUsageCalls.get(), "未消费 token，不应更新 last_used_*");
    }

    /**
     * 非 mtk_ 前缀（JWT bearer）直接放行，留给后续 anyRequest 规则处理。
     */
    @Test
    void shouldPassThroughForNonMtkAuthorization() throws Exception {
        StubHttpRequest req = new StubHttpRequest("GET", "/api/tokens");
        req.setHeader("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.xxx.yyy");
        StubHttpResponse resp = new StubHttpResponse();
        AtomicBoolean chained = new AtomicBoolean(false);
        FilterChain chain = (request, response) -> chained.set(true);

        filter.doFilterInternal(req, resp, chain);

        Assertions.assertTrue(chained.get());
        Assertions.assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "filter 不应写入 SecurityContext");
    }

    /**
     * 有效 token + 启用账号 → 写入 SecurityContext + ATTR_USER_ID/ATTR_AUTH_METHOD。
     */
    @Test
    void shouldAuthenticateValidToken() throws Exception {
        ApiToken row = stubToken(10L, 7, "readwrite", null);
        Account acc = stubAccount(7, "bob", Const.ROLE_DEFAULT, Boolean.TRUE);
        tokensByHash.put("mtk_validxxxxxxxxxxxxxxxxxxxxxxxxxx", row);
        accountsById.put(7, acc);

        StubHttpRequest req = new StubHttpRequest("POST", "/api/clients");
        req.setHeader("Authorization", "Bearer mtk_validxxxxxxxxxxxxxxxxxxxxxxxxxx");
        req.setRemoteAddr("203.0.113.5");
        StubHttpResponse resp = new StubHttpResponse();
        AtomicBoolean chained = new AtomicBoolean(false);
        FilterChain chain = (request, response) -> chained.set(true);

        filter.doFilterInternal(req, resp, chain);

        Assertions.assertTrue(chained.get());
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Assertions.assertNotNull(auth);
        Assertions.assertEquals("bob", ((User) auth.getPrincipal()).getUsername());
        Assertions.assertEquals(7, req.attributes.get(Const.ATTR_USER_ID));
        Assertions.assertEquals("ROLE_user", req.attributes.get(Const.ATTR_USER_ROLE));
        Assertions.assertEquals(10L, req.attributes.get(Const.ATTR_API_TOKEN));
        Assertions.assertEquals(Const.AUTH_METHOD_API_TOKEN,
                req.attributes.get(Const.ATTR_AUTH_METHOD));
        Assertions.assertEquals(1, recordUsageCalls.get());
    }

    /**
     * 无效 token → 401 + RestBean message。
     */
    @Test
    void shouldReturn401ForInvalidToken() throws Exception {
        StubHttpRequest req = new StubHttpRequest("GET", "/api/tokens");
        req.setHeader("Authorization", "Bearer mtk_nonexistent00000000000000000000000");
        StubHttpResponse resp = new StubHttpResponse();
        AtomicBoolean chained = new AtomicBoolean(false);
        FilterChain chain = (request, response) -> chained.set(true);

        filter.doFilterInternal(req, resp, chain);

        Assertions.assertFalse(chained.get(), "无效 token 不应放行");
        Assertions.assertEquals(401, resp.status);
        Assertions.assertTrue(resp.body().contains("无效"));
    }

    /**
     * 已禁用账号即使 token 合法也拒绝。
     */
    @Test
    void shouldReturn401ForDisabledAccount() throws Exception {
        ApiToken row = stubToken(11L, 8, "readwrite", null);
        Account acc = stubAccount(8, "disabled-user", Const.ROLE_DEFAULT, Boolean.FALSE);
        tokensByHash.put("mtk_ok000000000000000000000000000000", row);
        accountsById.put(8, acc);

        StubHttpRequest req = new StubHttpRequest("GET", "/api/clients");
        req.setHeader("Authorization", "Bearer mtk_ok000000000000000000000000000000");
        StubHttpResponse resp = new StubHttpResponse();
        AtomicBoolean chained = new AtomicBoolean(false);
        FilterChain chain = (request, response) -> chained.set(true);

        filter.doFilterInternal(req, resp, chain);

        Assertions.assertFalse(chained.get());
        Assertions.assertEquals(401, resp.status);
        Assertions.assertTrue(resp.body().contains("禁用"));
    }

    /**
     * readonly + POST → 403。
     */
    @Test
    void shouldReturn403ForReadOnlyTokenOnWrite() throws Exception {
        ApiToken row = stubToken(12L, 9, "readonly", null);
        Account acc = stubAccount(9, "user", Const.ROLE_DEFAULT, Boolean.TRUE);
        tokensByHash.put("mtk_ro00000000000000000000000000000000", row);
        accountsById.put(9, acc);

        StubHttpRequest req = new StubHttpRequest("POST", "/api/clients");
        req.setHeader("Authorization", "Bearer mtk_ro00000000000000000000000000000000");
        StubHttpResponse resp = new StubHttpResponse();
        AtomicBoolean chained = new AtomicBoolean(false);
        FilterChain chain = (request, response) -> chained.set(true);

        filter.doFilterInternal(req, resp, chain);

        Assertions.assertFalse(chained.get());
        Assertions.assertEquals(403, resp.status);
        Assertions.assertTrue(resp.body().contains("只读"));
    }

    /**
     * readonly + GET → 放行。
     */
    @Test
    void shouldAllowReadOnlyTokenOnGet() throws Exception {
        ApiToken row = stubToken(13L, 10, "readonly", null);
        Account acc = stubAccount(10, "u10", Const.ROLE_DEFAULT, Boolean.TRUE);
        tokensByHash.put("mtk_ro2xxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", row);
        accountsById.put(10, acc);

        StubHttpRequest req = new StubHttpRequest("GET", "/api/clients");
        req.setHeader("Authorization", "Bearer mtk_ro2xxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        StubHttpResponse resp = new StubHttpResponse();
        AtomicBoolean chained = new AtomicBoolean(false);
        FilterChain chain = (request, response) -> chained.set(true);

        filter.doFilterInternal(req, resp, chain);

        Assertions.assertTrue(chained.get());
        Assertions.assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * X-Api-Token 优先于 Authorization 头。
     */
    @Test
    void xApiTokenHeaderShouldTakePrecedence() throws Exception {
        ApiToken row = stubToken(14L, 11, "readwrite", null);
        Account acc = stubAccount(11, "u11", Const.ROLE_DEFAULT, Boolean.TRUE);
        tokensByHash.put("mtk_via_x_header00000000000000000000", row);
        accountsById.put(11, acc);

        StubHttpRequest req = new StubHttpRequest("GET", "/api/clients");
        req.setHeader("X-Api-Token", "mtk_via_x_header00000000000000000000");
        req.setHeader("Authorization", "Bearer mtk_other_token0000000000000000000");
        StubHttpResponse resp = new StubHttpResponse();
        AtomicBoolean chained = new AtomicBoolean(false);
        FilterChain chain = (request, response) -> chained.set(true);

        filter.doFilterInternal(req, resp, chain);

        Assertions.assertTrue(chained.get());
        Assertions.assertEquals(14L, req.attributes.get(Const.ATTR_API_TOKEN));
    }

    /**
     * 不存在 Authorization / X-Api-Token 时直接放行（不写 401，让 Spring Security 兜底）。
     */
    @Test
    void shouldPassThroughWhenNoTokenHeader() throws Exception {
        StubHttpRequest req = new StubHttpRequest("GET", "/api/tokens");
        StubHttpResponse resp = new StubHttpResponse();
        AtomicBoolean chained = new AtomicBoolean(false);
        FilterChain chain = (request, response) -> chained.set(true);

        filter.doFilterInternal(req, resp, chain);

        Assertions.assertTrue(chained.get());
    }

    private ApiToken stubToken(long id, int accountId, String scope, Date expiresAt) {
        ApiToken row = new ApiToken();
        row.setId(id);
        row.setAccountId(accountId);
        row.setName("t-" + id);
        row.setTokenHash("hash-" + id);
        row.setPrefixTail("mtk_xxxx…yyyy");
        row.setScope(scope);
        row.setExpiresAt(expiresAt);
        row.setCreatedAt(new Date());
        return row;
    }

    private Account stubAccount(int id, String username, String role, Boolean enabled) {
        Account a = new Account();
        a.setId(id);
        a.setUsername(username);
        a.setRole(role);
        a.setEnabled(enabled);
        return a;
    }

    /**
     * 最小化的 HttpServletRequest 实现：仅捕获 method / header / attribute / remoteAddr。
     */
    static class StubHttpRequest implements HttpServletRequest {
        final String method;
        final String requestURI;
        final Map<String, String> headers = new HashMap<>();
        final Map<String, Object> attributes = new HashMap<>();
        String remoteAddr;

        StubHttpRequest(String method, String requestURI) {
            this.method = method;
            this.requestURI = requestURI;
        }

        void setHeader(String name, String value) { headers.put(name, value); }
        void setRemoteAddr(String addr) { this.remoteAddr = addr; }

        @Override public String getMethod() { return method; }
        @Override public String getHeader(String name) { return headers.get(name); }
        @Override public String getRequestURI() { return requestURI; }
        @Override public String getRemoteAddr() { return remoteAddr; }
        @Override public void setAttribute(String name, Object value) { attributes.put(name, value); }
        @Override public Object getAttribute(String name) { return attributes.get(name); }
        @Override public java.util.Enumeration<String> getAttributeNames() {
            return java.util.Collections.enumeration(attributes.keySet());
        }
        @Override public void removeAttribute(String name) { attributes.remove(name); }

        // --- 以下均为 Servlet API 兼容方法，本测试不依赖 ---
        @Override public String getAuthType() { return null; }
        @Override public jakarta.servlet.http.Cookie[] getCookies() { return new jakarta.servlet.http.Cookie[0]; }
        @Override public long getDateHeader(String name) { return -1; }
        @Override public java.util.Enumeration<String> getHeaders(String name) {
            String v = headers.get(name);
            return v == null ? java.util.Collections.emptyEnumeration()
                    : java.util.Collections.enumeration(java.util.List.of(v));
        }
        @Override public java.util.Enumeration<String> getHeaderNames() {
            return java.util.Collections.enumeration(headers.keySet());
        }
        @Override public int getIntHeader(String name) { return -1; }
        @Override public String getPathInfo() { return null; }
        @Override public String getPathTranslated() { return null; }
        @Override public String getContextPath() { return ""; }
        @Override public String getQueryString() { return null; }
        @Override public String getRemoteUser() { return null; }
        @Override public boolean isUserInRole(String role) { return false; }
        @Override public java.security.Principal getUserPrincipal() { return null; }
        @Override public String getRequestedSessionId() { return null; }
        @Override public StringBuffer getRequestURL() { return new StringBuffer(requestURI); }
        @Override public String getServletPath() { return requestURI; }
        @Override public jakarta.servlet.http.HttpSession getSession(boolean create) { return null; }
        @Override public jakarta.servlet.http.HttpSession getSession() { return null; }
        @Override public String changeSessionId() { return null; }
        @Override public boolean isRequestedSessionIdValid() { return false; }
        @Override public boolean isRequestedSessionIdFromCookie() { return false; }
        @Override public boolean isRequestedSessionIdFromURL() { return false; }
        @Override public boolean authenticate(HttpServletResponse response) { return false; }
        @Override public void login(String username, String password) {}
        @Override public void logout() {}
        @Override public java.util.Collection<jakarta.servlet.http.Part> getParts() {
            return java.util.Collections.emptyList();
        }
        @Override public jakarta.servlet.http.Part getPart(String name) { return null; }
        @Override public <T extends jakarta.servlet.http.HttpUpgradeHandler> T upgrade(Class<T> handlerClass) { return null; }
        @Override public String getCharacterEncoding() { return null; }
        @Override public void setCharacterEncoding(String env) {}
        @Override public int getContentLength() { return -1; }
        @Override public long getContentLengthLong() { return -1L; }
        @Override public String getContentType() { return null; }
        @Override public jakarta.servlet.ServletInputStream getInputStream() { return null; }
        @Override public String getParameter(String name) { return null; }
        @Override public java.util.Enumeration<String> getParameterNames() {
            return java.util.Collections.emptyEnumeration();
        }
        @Override public String[] getParameterValues(String name) { return new String[0]; }
        @Override public Map<String, String[]> getParameterMap() { return java.util.Collections.emptyMap(); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public String getScheme() { return "http"; }
        @Override public String getServerName() { return "localhost"; }
        @Override public int getServerPort() { return 80; }
        @Override public java.io.BufferedReader getReader() { return null; }
        @Override public String getRemoteHost() { return remoteAddr; }
        @Override public java.util.Locale getLocale() { return java.util.Locale.ROOT; }
        @Override public java.util.Enumeration<java.util.Locale> getLocales() {
            return java.util.Collections.enumeration(java.util.List.of(java.util.Locale.ROOT));
        }
        @Override public boolean isSecure() { return false; }
        @Override public jakarta.servlet.RequestDispatcher getRequestDispatcher(String path) { return null; }
        @Override public int getRemotePort() { return 0; }
        @Override public String getLocalName() { return null; }
        @Override public String getLocalAddr() { return null; }
        @Override public int getLocalPort() { return 0; }
        @Override public jakarta.servlet.ServletContext getServletContext() { return null; }
        @Override public jakarta.servlet.AsyncContext startAsync() { return null; }
        @Override public jakarta.servlet.AsyncContext startAsync(jakarta.servlet.ServletRequest servletRequest,
                                                                  jakarta.servlet.ServletResponse servletResponse) { return null; }
        @Override public boolean isAsyncStarted() { return false; }
        @Override public boolean isAsyncSupported() { return false; }
        @Override public jakarta.servlet.AsyncContext getAsyncContext() { return null; }
        @Override public jakarta.servlet.DispatcherType getDispatcherType() { return jakarta.servlet.DispatcherType.REQUEST; }
        @Override public String getRequestId() { return ""; }
        @Override public String getProtocolRequestId() { return ""; }
        @Override public jakarta.servlet.ServletConnection getServletConnection() { return null; }
    }

    /**
     * 最小化的 HttpServletResponse 实现：捕获 status / body。
     */
    static class StubHttpResponse implements HttpServletResponse {
        int status = 200;
        final StringWriter body = new StringWriter();
        final PrintWriter writer = new PrintWriter(body);
        final AtomicReference<String> contentType = new AtomicReference<>();

        String body() { writer.flush(); return body.toString(); }

        @Override public void setStatus(int sc) { this.status = sc; }
        @Override public int getStatus() { return status; }
        @Override public void setContentType(String type) { contentType.set(type); }
        @Override public String getContentType() { return contentType.get(); }
        @Override public void setCharacterEncoding(String charset) {}
        @Override public String getCharacterEncoding() { return "utf-8"; }
        @Override public PrintWriter getWriter() { return writer; }

        // --- 兼容方法 ---
        @Override public void addCookie(jakarta.servlet.http.Cookie cookie) {}
        @Override public boolean containsHeader(String name) { return false; }
        @Override public String encodeURL(String url) { return url; }
        @Override public String encodeRedirectURL(String url) { return url; }
        @Override public void sendError(int sc, String msg) { this.status = sc; }
        @Override public void sendError(int sc) { this.status = sc; }
        @Override public void sendRedirect(String location) {}
        @Override public void setDateHeader(String name, long date) {}
        @Override public void addDateHeader(String name, long date) {}
        @Override public void setHeader(String name, String value) {}
        @Override public void addHeader(String name, String value) {}
        @Override public void setIntHeader(String name, int value) {}
        @Override public void addIntHeader(String name, int value) {}
        @Override public String getHeader(String name) { return null; }
        @Override public java.util.Collection<String> getHeaders(String name) { return java.util.Collections.emptyList(); }
        @Override public java.util.Collection<String> getHeaderNames() { return java.util.Collections.emptyList(); }
        @Override public jakarta.servlet.ServletOutputStream getOutputStream() { return null; }
        @Override public void setContentLength(int len) {}
        @Override public void setContentLengthLong(long len) {}
        @Override public void setBufferSize(int size) {}
        @Override public int getBufferSize() { return 0; }
        @Override public void flushBuffer() { writer.flush(); }
        @Override public void resetBuffer() {}
        @Override public boolean isCommitted() { return false; }
        @Override public void reset() {}
        @Override public void setLocale(java.util.Locale loc) {}
        @Override public java.util.Locale getLocale() { return java.util.Locale.ROOT; }
    }
}
