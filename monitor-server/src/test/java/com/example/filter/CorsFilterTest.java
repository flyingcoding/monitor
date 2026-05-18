package com.example.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/**
 * CorsFilter origin 白名单解析单测（v1.2 PRD R30 / AC12）。
 */
class CorsFilterTest {

    private CorsFilter newCorsFilter(String origin) {
        CorsFilter f = new CorsFilter();
        ReflectionTestUtils.setField(f, "origin", origin);
        ReflectionTestUtils.setField(f, "credentials", false);
        ReflectionTestUtils.setField(f, "methods", "*");
        ReflectionTestUtils.invokeMethod(f, "parseOriginConfig");
        return f;
    }

    /**
     * 通配符 {@code *} 模式回显请求 Origin。
     */
    @Test
    void wildcardEchoesRequestOrigin() throws Exception {
        CorsFilter f = newCorsFilter("*");
        Assertions.assertTrue(f.isWildcardForTest());
        Map<String, String> respHeaders = new HashMap<>();
        f.doFilter(stubReq("https://anywhere.example.com"), stubResp(respHeaders), (req, resp) -> {});
        Assertions.assertEquals("https://anywhere.example.com", respHeaders.get("Access-Control-Allow-Origin"));
    }

    /**
     * 空字符串配置（夸张容错路径）也降级为通配。
     */
    @Test
    void blankConfigDefaultsToWildcard() {
        CorsFilter f = newCorsFilter("");
        Assertions.assertTrue(f.isWildcardForTest());
    }

    /**
     * 白名单匹配命中：回显当前 Origin。
     */
    @Test
    void whitelistMatchEchoesOrigin() throws Exception {
        CorsFilter f = newCorsFilter("https://a.com,https://b.com");
        Assertions.assertFalse(f.isWildcardForTest());
        Assertions.assertEquals(2, f.getOriginWhitelistForTest().size());

        Map<String, String> respHeaders = new HashMap<>();
        f.doFilter(stubReq("https://b.com"), stubResp(respHeaders), (req, resp) -> {});
        Assertions.assertEquals("https://b.com", respHeaders.get("Access-Control-Allow-Origin"));
    }

    /**
     * API Token 推荐走 X-Api-Token，自定义头必须出现在 CORS 允许列表里。
     */
    @Test
    void allowHeadersShouldIncludeApiTokenHeader() throws Exception {
        CorsFilter f = newCorsFilter("*");
        Map<String, String> respHeaders = new HashMap<>();
        f.doFilter(stubReq("https://client.example.com"), stubResp(respHeaders), (req, resp) -> {});

        String allowHeaders = respHeaders.get("Access-Control-Allow-Headers");
        Assertions.assertNotNull(allowHeaders);
        Assertions.assertTrue(allowHeaders.contains("X-Api-Token"),
                "API Token 自定义头必须允许跨域预检");
    }

    /**
     * AC12：白名单外的 Origin 不应回写 ACAO 头。
     */
    @Test
    void whitelistMissShouldNotWriteAcao() throws Exception {
        CorsFilter f = newCorsFilter("https://a.com,https://b.com");
        Map<String, String> respHeaders = new HashMap<>();
        f.doFilter(stubReq("https://c.com"), stubResp(respHeaders), (req, resp) -> {});
        Assertions.assertFalse(respHeaders.containsKey("Access-Control-Allow-Origin"),
                "AC12: 非白名单 Origin 不应返回 ACAO");
    }

    /**
     * 缺失 Origin 头时，白名单模式不写 ACAO（避免泄露默认 origin）。
     */
    @Test
    void whitelistWithMissingOriginHeaderShouldNotWriteAcao() throws Exception {
        CorsFilter f = newCorsFilter("https://a.com");
        Map<String, String> respHeaders = new HashMap<>();
        f.doFilter(stubReq(null), stubResp(respHeaders), (req, resp) -> {});
        Assertions.assertFalse(respHeaders.containsKey("Access-Control-Allow-Origin"));
    }

    /**
     * 配置中包含空白条目时被过滤掉。
     */
    @Test
    void whitelistTrimsWhitespace() {
        CorsFilter f = newCorsFilter("  https://a.com , https://b.com  ");
        Assertions.assertTrue(f.getOriginWhitelistForTest().contains("https://a.com"));
        Assertions.assertTrue(f.getOriginWhitelistForTest().contains("https://b.com"));
        Assertions.assertEquals(2, f.getOriginWhitelistForTest().size());
    }

    /**
     * 构造一个仅返回 Origin 头的最小 HttpServletRequest 代理。
     */
    private HttpServletRequest stubReq(String origin) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class[]{HttpServletRequest.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getHeader" -> {
                            return "Origin".equals(args[0]) ? origin : null;
                        }
                        case "getMethod" -> { return "GET"; }
                        case "getRequestURI", "getServletPath" -> { return "/api/anything"; }
                        default -> { return null; }
                    }
                });
    }

    /**
     * 构造一个仅捕获响应头写入的最小 HttpServletResponse 代理。
     */
    private HttpServletResponse stubResp(Map<String, String> respHeaders) {
        return (HttpServletResponse) Proxy.newProxyInstance(
                HttpServletResponse.class.getClassLoader(),
                new Class[]{HttpServletResponse.class},
                (proxy, method, args) -> {
                    if ("addHeader".equals(method.getName()) && args != null && args.length == 2) {
                        respHeaders.put((String) args[0], (String) args[1]);
                    }
                    return null;
                });
    }
}
