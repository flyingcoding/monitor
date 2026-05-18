package com.example.filter;

import com.example.utils.Const;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 跨域配置过滤器，仅处理跨域，添加跨域响应头。
 *
 * <p>v1.2 PRD R30/AC12 增强：{@code spring.web.cors.origin} 支持
 * <ul>
 *   <li>{@code *} —— 通配符，回显请求 Origin（dev 默认）；</li>
 *   <li>逗号分隔的精确 origin 列表，如 {@code https://a.com,https://b.com} —— 仅命中白名单时回显，否则不返回 ACAO 头。</li>
 * </ul>
 */
@Component
@Order(Const.ORDER_CORS)
public class CorsFilter extends HttpFilter {

    @Value("${spring.web.cors.origin}")
    String origin;

    @Value("${spring.web.cors.credentials}")
    boolean credentials;

    @Value("${spring.web.cors.methods}")
    String methods;

    /**
     * 解析后的 origin 白名单：trim + 去空 + 保留输入顺序。仅当配置值非 {@code *} 时使用。
     */
    private Set<String> originWhitelist = Set.of();

    /**
     * 是否为通配符模式（{@code *} 或空配置）。
     */
    private boolean wildcardOrigin = false;

    @PostConstruct
    void parseOriginConfig() {
        if (origin == null || origin.isBlank() || "*".equals(origin.trim())) {
            wildcardOrigin = true;
            originWhitelist = Set.of();
            return;
        }
        wildcardOrigin = false;
        originWhitelist = new LinkedHashSet<>();
        for (String item : origin.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                originWhitelist.add(trimmed);
            }
        }
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        this.addCorsHeader(request, response);
        chain.doFilter(request, response);
    }

    /**
     * 添加所有跨域相关响应头；origin 不在白名单时不写 ACAO 头（浏览器视为被拒）。
     * @param request 请求
     * @param response 响应
     */
    private void addCorsHeader(HttpServletRequest request, HttpServletResponse response) {
        String resolved = this.resolveOrigin(request);
        if (resolved != null) {
            response.addHeader("Access-Control-Allow-Origin", resolved);
            response.addHeader("Access-Control-Allow-Methods", this.resolveMethod());
            response.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Api-Token");
            if (credentials) {
                response.addHeader("Access-Control-Allow-Credentials", "true");
            }
        }
    }

    /**
     * 解析配置文件中的请求方法
     * @return 解析得到的请求头值
     */
    private String resolveMethod(){
        return methods.equals("*") ? "GET, HEAD, POST, PUT, DELETE, OPTIONS, TRACE, PATCH" : methods;
    }

    /**
     * 解析允许回显的 Origin：
     * <ol>
     *   <li>通配符模式：回显请求 Origin（与历史行为一致）；</li>
     *   <li>白名单模式：仅当请求 Origin 严格匹配白名单中的某一项才回显，否则返回 {@code null}（不写 ACAO 头）。</li>
     * </ol>
     *
     * @param request 请求
     * @return 允许写入 ACAO 的值；不允许时返回 null
     */
    private String resolveOrigin(HttpServletRequest request){
        String requestOrigin = request.getHeader("Origin");
        if (wildcardOrigin) {
            // 与历史行为兼容：未带 Origin 头时回显占位 "*"
            return requestOrigin != null ? requestOrigin : "*";
        }
        if (requestOrigin == null || requestOrigin.isBlank()) {
            return null;
        }
        // 精确匹配（区分大小写、含 scheme 与 port），覆盖 PRD AC12
        if (originWhitelist.contains(requestOrigin)) {
            return requestOrigin;
        }
        return null;
    }

    /**
     * 测试可见的白名单视图（仅供单元测试）。
     */
    Set<String> getOriginWhitelistForTest() {
        return Set.copyOf(originWhitelist);
    }

    /**
     * 测试可见的通配标志（仅供单元测试）。
     */
    boolean isWildcardForTest() {
        return wildcardOrigin;
    }
}
