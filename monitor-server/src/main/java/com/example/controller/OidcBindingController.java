package com.example.controller;

import com.example.entity.RestBean;
import com.example.entity.vo.response.OidcBindingVO;
import com.example.service.AccountOidcBindingService;
import com.example.service.OidcBindingIntentService;
import com.example.utils.Const;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 个人 OIDC 绑定管理 API（需登录）。
 *
 * <p>P2-2 修复：新增 intent 流（{@link #issueIntent} + {@link #startBinding}），解决"已登录账号点击
 * 绑定新 Provider"时 SuccessHandler 拿不到 accountId 的问题（浏览器 SPA 把 JWT 存 localStorage，
 * 直接 {@code window.location.href = '/oauth2/authorization/<name>'} 不会携带 Authorization）。
 *
 * <p>三步流程：
 * <ol>
 *   <li>前端 {@code POST /api/oidc/bindings/intent}（JWT）→ 拿到 intent token；</li>
 *   <li>前端跳转 {@code GET /api/oidc/bindings/start/{provider}?intentToken=<token>}：start 校验 intent
 *       并把 accountId 写入 HTTP session，再 302 到 {@code /oauth2/authorization/{provider}}；</li>
 *   <li>{@code OidcSuccessHandler} 检查 session 是否有
 *       {@link #SESSION_BINDING_ACCOUNT_ID}，有则按"绑定"语义落 account_oidc_binding 而非
 *       "登录/自动创建"。</li>
 * </ol>
 */
@Slf4j
@RestController
@RequestMapping("/api/oidc/bindings")
public class OidcBindingController {

    /**
     * SuccessHandler 用以识别绑定流程的 session 属性 key。
     */
    public static final String SESSION_BINDING_ACCOUNT_ID = "oidc.binding.accountId";

    @Resource
    private AccountOidcBindingService accountOidcBindingService;

    @Resource
    private OidcBindingIntentService oidcBindingIntentService;

    /**
     * 列出当前账号的全部 OIDC 绑定。
     */
    @GetMapping
    public RestBean<List<OidcBindingVO>> list(@RequestAttribute(Const.ATTR_USER_ID) int userId) {
        return RestBean.success(accountOidcBindingService.listByAccount(userId));
    }

    /**
     * 生成绑定意图 token。
     *
     * <p>必须使用 JWT（浏览器交互登录）发起：与 {@link ApiTokenController} 同款，禁止 API Token
     * 自身签发能改变账号鉴权方式的凭据（"token 不能管理 auth"）。命中 API Token 时返回 403。
     * 内部生成 5min TTL 的随机 intent token 关联 accountId，前端只能用它跳转一次。
     *
     * @param request Servlet 请求（用于读取 ATTR_AUTH_METHOD）
     * @param userId  当前账号 ID（由 JwtFilter 写入）
     * @return 含 {@code intentToken} 与目标 URL 模板的载荷
     */
    @PostMapping("/intent")
    public RestBean<Map<String, Object>> issueIntent(HttpServletRequest request,
                                                     @RequestAttribute(Const.ATTR_USER_ID) int userId) {
        if (isApiTokenAuth(request)) {
            return RestBean.forbidden("不允许通过 API Token 发起 OIDC 绑定");
        }
        String token = oidcBindingIntentService.issue(userId);
        return RestBean.success(Map.of(
                "intentToken", token,
                "ttlSeconds", 300
        ));
    }

    /**
     * 判断当前请求是否通过 API Token 鉴权。与 {@link ApiTokenController#isApiTokenAuth} 同源逻辑。
     *
     * @param request Servlet 请求
     * @return 是 API Token 时返回 true
     */
    private boolean isApiTokenAuth(HttpServletRequest request) {
        Object method = request.getAttribute(Const.ATTR_AUTH_METHOD);
        return Const.AUTH_METHOD_API_TOKEN.equals(method);
    }

    /**
     * 进入 OAuth 授权端点的"绑定专用"入口。
     *
     * <p>路径在 SecurityConfiguration permitAll 中放行（intent token 本身即凭据）。
     * 校验通过后把 accountId 写入 HTTP session 并 302 到 Spring Security 标准的
     * {@code /oauth2/authorization/{provider}}；后者在同一 session 内完成回调，
     * {@code OidcSuccessHandler} 读取 session 属性区分"绑定 vs 登录"。
     *
     * <p>异常路径：intent 无效 / 过期 → 302 回登录页携带 {@code oidc_error=invalid_intent}。
     *
     * @param provider OIDC Provider 名（registrationId）
     * @param intent   一次性 intent token
     * @param request  Servlet 请求
     * @param response Servlet 响应
     */
    @GetMapping("/start/{provider}")
    public void startBinding(@PathVariable String provider,
                             @RequestParam("intentToken") String intent,
                             HttpServletRequest request,
                             HttpServletResponse response) throws IOException {
        Optional<Integer> accountId = oidcBindingIntentService.consume(intent);
        if (accountId.isEmpty()) {
            log.warn("OIDC binding start rejected: invalid or expired intent token");
            response.sendRedirect("/?oidc_error=invalid_intent&message="
                    + URLEncoder.encode("绑定意图已过期，请重新发起绑定", StandardCharsets.UTF_8));
            return;
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(SESSION_BINDING_ACCOUNT_ID, accountId.get());
        log.info("OIDC binding start accountId={} provider={}", accountId.get(), provider);
        response.sendRedirect("/oauth2/authorization/" + URLEncoder.encode(provider, StandardCharsets.UTF_8));
    }

    /**
     * 解除指定 provider 的绑定。
     *
     * <p>安全校验：解绑后账号必须仍有至少一种登录方式（本地密码或其他 OIDC 绑定）。
     *
     * <ul>
     *   <li>400 {@code binding_not_found} — 当前账号未绑该 provider；</li>
     *   <li>400 {@code last_login_method} — 解绑后将失去全部登录方式；</li>
     *   <li>200 success — 解绑成功。</li>
     * </ul>
     */
    @DeleteMapping("/{provider}")
    public RestBean<Void> unbind(HttpServletRequest request,
                                 @PathVariable String provider,
                                 @RequestAttribute(Const.ATTR_USER_ID) int userId) {
        if (isApiTokenAuth(request)) {
            return RestBean.forbidden("不允许通过 API Token 解除 OIDC 绑定");
        }
        AccountOidcBindingService.UnbindResult result = accountOidcBindingService.unbind(userId, provider);
        return switch (result) {
            case OK -> RestBean.success();
            case BINDING_NOT_FOUND -> RestBean.failure(400, "未找到该 Provider 的绑定");
            case LAST_LOGIN_METHOD -> RestBean.failure(400, "解绑后将失去登录方式，请先设置密码或绑定其他 Provider");
        };
    }
}
