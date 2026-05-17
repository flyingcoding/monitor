package com.example.config.security.oidc;

import com.example.entity.dto.Account;
import com.example.service.AccountOidcBindingService;
import com.example.service.AccountService;
import com.example.utils.Const;
import com.example.utils.JwtUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * OIDC 登录成功 Handler。
 *
 * <p>流程：
 * <ol>
 *   <li>从 {@link OAuth2AuthenticationToken#getAuthorizedClientRegistrationId()} 取得 provider；</li>
 *   <li>从 {@link OidcUser} 取 subject / email / email_verified；</li>
 *   <li>调用 {@link AccountService#resolveOrCreateByOidc} 解析或创建账号（D3）；</li>
 *   <li>调用 {@link AccountOidcBindingService#upsert} 写绑定（已绑则刷新 email）；</li>
 *   <li>用 {@link JwtUtils#createJwt} 生成 JWT，redirect 到 {@code /?oidc_token=...&expire=...} 由前端持久化。</li>
 * </ol>
 *
 * <p>{@link OidcLoginException} 不在本类中处理，由失败 handler 接管（Spring Security 在抛出
 * {@link AuthenticationException} 子类时会触发 failure handler）。
 */
@Slf4j
@Component
public class OidcSuccessHandler implements AuthenticationSuccessHandler {

    @Resource
    private AccountService accountService;

    @Resource
    private AccountOidcBindingService accountOidcBindingService;

    @Resource
    private JwtUtils jwtUtils;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        String provider = null;
        if (authentication instanceof OAuth2AuthenticationToken token) {
            provider = token.getAuthorizedClientRegistrationId();
        }
        if (!(authentication.getPrincipal() instanceof OidcUser oidc)) {
            log.error("OIDC 成功回调 principal 非 OidcUser: {}", authentication.getPrincipal());
            redirectError(response, OidcLoginErrorCode.INTERNAL_ERROR, "OIDC 回调主体格式异常");
            return;
        }
        String subject = oidc.getSubject();
        String email = oidc.getEmail();
        Boolean emailVerified = oidc.getEmailVerified();

        if (provider == null || subject == null) {
            log.error("OIDC 成功回调缺少 provider/sub: provider={} sub={}", provider, subject);
            redirectError(response, OidcLoginErrorCode.INTERNAL_ERROR, "OIDC 回调缺少必要字段");
            return;
        }

        Account account;
        try {
            account = accountService.resolveOrCreateByOidc(provider, subject, email, emailVerified);
        } catch (OidcLoginException ex) {
            redirectError(response, ex.getErrorCode(), ex.getMessage());
            return;
        } catch (Exception ex) {
            log.error("OIDC resolveOrCreateByOidc 异常 provider={} sub={}", provider, subject, ex);
            redirectError(response, OidcLoginErrorCode.INTERNAL_ERROR, "OIDC 登录失败");
            return;
        }

        if (account == null) {
            redirectError(response, OidcLoginErrorCode.ACCOUNT_NOT_FOUND, "未找到对应账号");
            return;
        }

        try {
            accountOidcBindingService.upsert(account.getId(), provider, subject, email);
        } catch (Exception ex) {
            log.warn("OIDC 绑定 upsert 失败 accountId={} provider={}: {}",
                    account.getId(), provider, ex.getMessage());
            // 绑定失败不阻塞登录，下次回调仍会尝试 upsert
        }

        String role = account.getRole();
        if (role == null || role.isBlank()) {
            role = Const.ROLE_DEFAULT;
        }
        UserDetails user = User
                .withUsername(account.getUsername())
                .password("******")
                .authorities(List.of(new SimpleAuthority("ROLE_" + role)))
                .build();
        String jwt = jwtUtils.createJwt(user, account.getUsername(), account.getId());
        if (jwt == null) {
            redirectError(response, OidcLoginErrorCode.INTERNAL_ERROR, "登录验证频繁，请稍后再试");
            return;
        }
        Date expire = jwtUtils.expireTime();

        request.setAttribute(Const.ATTR_USER_ID, account.getId());
        request.setAttribute(Const.ATTR_USER_ROLE, role);
        request.setAttribute(Const.ATTR_AUTH_METHOD, Const.AUTH_METHOD_OIDC);
        log.info("OIDC 登录成功 accountId={} provider={} username={}", account.getId(), provider, account.getUsername());

        // 重定向到根路径，前端登录页拦截 query 参数并持久化到 storage
        String redirect = "/?oidc_token=" + URLEncoder.encode(jwt, StandardCharsets.UTF_8)
                + "&expire=" + expire.getTime();
        response.sendRedirect(redirect);
    }

    /**
     * 重定向到登录页携带错误码与文案，前端按 query 弹出 ElMessage。
     */
    private void redirectError(HttpServletResponse response, String code, String message) throws IOException {
        String redirect = "/?oidc_error=" + URLEncoder.encode(code == null ? "internal_error" : code, StandardCharsets.UTF_8)
                + "&message=" + URLEncoder.encode(message == null ? "OIDC 登录失败" : message, StandardCharsets.UTF_8);
        response.sendRedirect(redirect);
    }

    /**
     * 简单的 GrantedAuthority 实现，避免与 Spring 6 旧版本兼容性问题。
     */
    private record SimpleAuthority(String authority) implements org.springframework.security.core.GrantedAuthority {
        @Override
        public String getAuthority() {
            return authority;
        }
    }
}
