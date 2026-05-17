package com.example.config.security.oidc;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * OIDC 登录失败 Handler。
 *
 * <p>统一重定向到登录页（前端路径 {@code /}），附带 {@code oidc_error} 与 {@code message} query：
 * <ul>
 *   <li>{@link OidcLoginException} → 携带其 errorCode（如 {@code account_not_found}）；</li>
 *   <li>{@link OAuth2AuthenticationException} → {@code provider_disabled} 或 {@code internal_error}；</li>
 *   <li>其他 → {@code internal_error}。</li>
 * </ul>
 *
 * <p>前端 LoginPage 在 mounted 时读取 {@code window.location.search} 并通过 ElMessage 显示。
 */
@Slf4j
@Component
public class OidcFailureHandler implements AuthenticationFailureHandler {

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        String code;
        String message;
        if (exception instanceof OidcLoginException ex) {
            code = ex.getErrorCode();
            message = ex.getMessage();
            log.info("OIDC 登录被拒 code={} msg={}", code, message);
        } else if (exception instanceof OAuth2AuthenticationException ex) {
            code = ex.getError() != null ? ex.getError().getErrorCode() : OidcLoginErrorCode.PROVIDER_DISABLED;
            message = ex.getError() != null && ex.getError().getDescription() != null
                    ? ex.getError().getDescription()
                    : "OAuth2 鉴权失败";
            log.warn("OIDC 鉴权异常 code={} msg={}", code, message);
        } else {
            code = OidcLoginErrorCode.INTERNAL_ERROR;
            message = exception.getMessage() == null ? "OIDC 登录失败" : exception.getMessage();
            log.warn("OIDC 登录未分类失败: {}", message);
        }
        String redirect = "/?oidc_error=" + URLEncoder.encode(code == null ? "internal_error" : code, StandardCharsets.UTF_8)
                + "&message=" + URLEncoder.encode(message == null ? "OIDC 登录失败" : message, StandardCharsets.UTF_8);
        response.sendRedirect(redirect);
    }
}
