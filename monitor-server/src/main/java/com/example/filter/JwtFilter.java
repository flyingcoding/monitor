package com.example.filter;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.example.entity.RestBean;
import com.example.entity.dto.Client;
import com.example.service.ClientService;
import com.example.service.PermissionService;
import com.example.utils.Const;
import com.example.utils.JwtUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;

/**
 * 对请求头中的 JWT 令牌进行校验，并将用户信息附加到请求上下文。
 */
@Component
public class JwtFilter extends OncePerRequestFilter {

    @Resource
    private JwtUtils utils;

    @Resource
    private ClientService clientService;

    @Resource
    private PermissionService permissionService;

    /**
     * 执行 JWT 与客户端 token 的鉴权过滤。
     *
     * @param request 请求
     * @param response 响应
     * @param filterChain 过滤器链
     * @throws ServletException servlet异常
     * @throws IOException io异常
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @NotNull HttpServletResponse response,
                                    @NotNull FilterChain filterChain) throws ServletException, IOException {
        String requestUri = request.getRequestURI();
        boolean terminalRequest = requestUri.startsWith("/terminal/");
        boolean sftpRequest = requestUri.startsWith("/sftp/");
        boolean clientWebSocketRequest = terminalRequest || sftpRequest;
        String authorization = this.resolveAuthorization(request, requestUri);

        if (requestUri.startsWith("/monitor")) {
            if (!requestUri.endsWith("/register")) {
                Client client = clientService.findClientByToken(authorization);
                if (client == null) {
                    this.writeFailure(response, 401, "未注册");
                    return;
                } else {
                    request.setAttribute(Const.ATTR_CLIENT, client);
                }
            }
        } else {
            DecodedJWT jwt = utils.resolveJwt(authorization);
            if (clientWebSocketRequest && jwt == null) {
                this.writeFailure(response, 401, "未登录或令牌已失效");
                return;
            }
            if (jwt != null) {
                UserDetails user = utils.toUser(jwt);
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);

                int userId = utils.toId(jwt);
                String userRole = new ArrayList<>(user.getAuthorities()).get(0).getAuthority();
                request.setAttribute(Const.ATTR_USER_ID, userId);
                request.setAttribute(Const.ATTR_USER_ROLE, userRole);

                if (clientWebSocketRequest) {
                    Integer clientId = this.resolveClientWebSocketClientId(requestUri, terminalRequest);
                    if (clientId == null) {
                        this.writeFailure(response, 400, "WebSocket地址非法");
                        return;
                    }
                    if (!this.accessShell(userId, userRole, clientId)) {
                        this.writeFailure(response, 401, "无权访问");
                        return;
                    }
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 解析请求中的授权令牌，优先读取 Authorization，其次从 query token 回填。
     *
     * @param request 请求
     * @param requestUri 请求路径
     * @return 规范化后的 Authorization 值
     */
    private String resolveAuthorization(HttpServletRequest request, String requestUri) {
        String authorization = request.getHeader("Authorization");
        boolean allowQueryToken = requestUri.startsWith("/api/sse/")
                || requestUri.startsWith("/api/v1/sse/")
                || requestUri.startsWith("/terminal/")
                || requestUri.startsWith("/sftp/");
        if (authorization == null && allowQueryToken) {
            String tokenParam = request.getParameter("token");
            if (tokenParam != null && !tokenParam.isBlank()) {
                return "Bearer " + tokenParam;
            }
        }
        return authorization;
    }

    /**
     * 从终端或 SFTP WebSocket 请求路径中提取客户端 ID。
     *
     * @param requestUri 请求路径
     * @param terminalRequest 是否为终端请求
     * @return 客户端ID，解析失败返回 null
     */
    private Integer resolveClientWebSocketClientId(String requestUri, boolean terminalRequest) {
        try {
            return Integer.parseInt(requestUri.substring(terminalRequest ? 10 : 6));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 输出标准失败响应。
     *
     * @param response 响应
     * @param code 状态码
     * @param message 消息
     * @throws IOException IO异常
     */
    private void writeFailure(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(code);
        response.setCharacterEncoding("utf-8");
        response.getWriter().write(RestBean.failure(code, message).asJsonString());
    }

    /**
     * 判断用户是否有访问目标客户端终端的权限。
     *
     * @param userId 用户ID
     * @param userRole 用户角色
     * @param clientId 客户端ID
     * @return 是否允许访问
     */
    private boolean accessShell(int userId, String userRole, int clientId) {
        return permissionService.canAccessClient(userId, userRole, clientId);
    }
}
