package com.example.filter;

import com.example.entity.RestBean;
import com.example.entity.dto.Account;
import com.example.entity.dto.ApiToken;
import com.example.service.AccountService;
import com.example.service.ApiTokenService;
import com.example.utils.Const;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * API Token 校验过滤器（v1.2 prd R15）。
 *
 * <p>排在 {@link JwtFilter} 之后；当 {@code SecurityContext} 已认证（即 JWT 校验通过）时跳过，
 * 避免重复鉴权。否则识别 {@code Authorization: Bearer mtk_*} / {@code X-Api-Token: mtk_*}：
 *
 * <ol>
 *   <li>调用 {@link ApiTokenService#validateAndResolve(String)} 解析；</li>
 *   <li>校验绑定的 {@link Account} 是否启用（{@code enabled != FALSE}）；</li>
 *   <li>校验 scope：{@code readonly} 只放行 GET/HEAD/OPTIONS，其它返回 403；</li>
 *   <li>写入 {@code SecurityContextHolder} 与请求属性，便于下游 controller 复用 JwtFilter 的语义；</li>
 *   <li>异步触发 {@link ApiTokenService#recordUsage(long, String)} 更新 last_used_*（60s 节流）。</li>
 * </ol>
 *
 * <p>未携带 {@code mtk_} 前缀时直接放行：让原生 Spring Security 在后续 anyRequest 规则里以 401 处理；
 * 这与 prd 中 "都没命中走 401" 的契约一致（401 由 Spring Security 而非过滤器主动写入）。
 */
@Slf4j
@Component
public class ApiTokenFilter extends OncePerRequestFilter {

    /**
     * 只读 token 允许的 HTTP 方法。{@code TRACE} 不在内（无业务用途）。
     */
    private static final Set<String> READ_ONLY_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    @Resource
    private ApiTokenService apiTokenService;

    @Resource
    private AccountService accountService;

    @Override
    protected void doFilterInternal(@NotNull HttpServletRequest request,
                                    @NotNull HttpServletResponse response,
                                    @NotNull FilterChain filterChain) throws ServletException, IOException {
        // 1. 已认证则跳过（JWT / 表单登录已写入 SecurityContext）
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        // 2. 提取明文 token：优先 X-Api-Token，其次 Authorization Bearer
        String rawToken = extractToken(request);
        if (rawToken == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // 3. 校验 + 解析
        Optional<ApiToken> resolved = apiTokenService.validateAndResolve(rawToken);
        if (resolved.isEmpty()) {
            writeFailure(response, 401, "API Token 无效或已过期");
            return;
        }
        ApiToken record = resolved.get();

        // 4. 账号状态校验：被禁用账号即拒
        Account account = accountService.getById(record.getAccountId());
        if (account == null) {
            // 绑定账号已被删除，token 是孤儿；拒绝并记录
            log.warn("API Token tokenId={} 绑定的 accountId={} 已不存在，拒绝鉴权",
                    record.getId(), record.getAccountId());
            writeFailure(response, 401, "API Token 关联账号不存在");
            return;
        }
        if (Boolean.FALSE.equals(account.getEnabled())) {
            writeFailure(response, 401, "账号已被禁用");
            return;
        }

        // 5. scope 校验：readonly 仅允许只读方法
        if ("readonly".equals(record.getScope()) && !READ_ONLY_METHODS.contains(request.getMethod())) {
            writeFailure(response, 403, "API Token 仅具备只读权限");
            return;
        }

        // 6. 构造 Spring Security Authentication（保持与 JwtFilter 一致的角色拼写：ROLE_<role>）
        String role = account.getRole();
        if (role == null || role.isBlank()) {
            role = Const.ROLE_DEFAULT;
        }
        String authorityName = role.startsWith("ROLE_") ? role : "ROLE_" + role;

        UserDetails user = User.withUsername(account.getUsername())
                .password("******")
                .authorities(authorityName)
                .build();
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);

        request.setAttribute(Const.ATTR_USER_ID, account.getId());
        request.setAttribute(Const.ATTR_USER_ROLE, authorityName);
        request.setAttribute(Const.ATTR_API_TOKEN, record.getId());
        request.setAttribute(Const.ATTR_AUTH_METHOD, Const.AUTH_METHOD_API_TOKEN);

        // 7. 异步更新 last_used_at / last_used_ip（不阻塞当前请求）
        try {
            apiTokenService.recordUsage(record.getId(), request.getRemoteAddr());
        } catch (RuntimeException e) {
            // recordUsage 内部已有 catch；这里再兜底防止 @Async 装配异常影响请求
            log.warn("recordUsage 调用失败 tokenId={} reason={}", record.getId(), e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 从请求中提取明文 token。
     *
     * <ol>
     *   <li>优先读 {@code X-Api-Token}：客户端使用 API Token 时的推荐 header；</li>
     *   <li>退而求其次读 {@code Authorization}：值必须以 {@code Bearer mtk_} 开头，避免与 JWT 流串扰。</li>
     * </ol>
     *
     * @param request 请求
     * @return 明文 token 或 null
     */
    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("X-Api-Token");
        if (header != null && !header.isBlank()) {
            String trimmed = header.trim();
            if (trimmed.startsWith(Const.API_TOKEN_PREFIX)) {
                return trimmed;
            }
            return null;
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            String value = auth.substring(7).trim();
            if (value.startsWith(Const.API_TOKEN_PREFIX)) {
                return value;
            }
        }
        return null;
    }

    /**
     * 输出标准失败响应（与 JwtFilter 同款 {@link RestBean} JSON）。
     *
     * @param response 响应
     * @param code     code（401 / 403）
     * @param message  消息
     * @throws IOException IO 异常
     */
    private void writeFailure(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(code);
        response.setCharacterEncoding("utf-8");
        response.setContentType("application/json;charset=utf-8");
        response.getWriter().write(RestBean.failure(code, message).asJsonString());
    }
}
