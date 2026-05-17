package com.example.config;

import com.example.config.security.oidc.OidcFailureHandler;
import com.example.config.security.oidc.OidcSuccessHandler;
import com.example.entity.RestBean;
import com.example.entity.dto.Account;
import com.example.entity.vo.response.AuthorizeVO;
import com.example.filter.ApiTokenFilter;
import com.example.filter.JwtFilter;
import com.example.filter.RequestLogFilter;
import com.example.mapper.struct.AccountStructMapper;
import com.example.service.AccountService;
import com.example.utils.Const;
import com.example.utils.JwtUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * SpringSecurity相关配置
 */
@Configuration
public class SecurityConfiguration {

    @Resource
    JwtFilter jwtFilter;

    @Resource
    RequestLogFilter requestLogFilter;

    @Resource
    ApiTokenFilter apiTokenFilter;

    @Resource
    OidcSuccessHandler oidcSuccessHandler;

    @Resource
    OidcFailureHandler oidcFailureHandler;

    @Resource
    JwtUtils utils;

    @Resource
    AccountService service;
    @Resource
    AccountStructMapper accountStructMapper;

    /**
     * 针对于 SpringSecurity 6 的新版配置方法
     * @param http 配置器
     * @return 自动构建的内置过滤器链
     * @throws Exception 可能的异常
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(conf -> conf
                        .requestMatchers("/terminal/**").authenticated()
                        .requestMatchers("/api/auth/**", "/error").permitAll()
                        .requestMatchers("/monitor/**").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/doc.html/**","/webjars/**","/favicon.ico").permitAll()
                        // v1.2 共享层：OAuth2 入口、回调路径、公开 OIDC Provider 列表、公开状态页 API
                        .requestMatchers("/oauth2/**", "/login/oauth2/code/**").permitAll()
                        .requestMatchers("/api/oidc/providers/public").permitAll()
                        // P2-2：绑定意图启动端点用 intent token 自鉴权（无 JWT），permitAll 让浏览器导航能命中
                        .requestMatchers("/api/oidc/bindings/start/**").permitAll()
                        .requestMatchers("/api/status/**").permitAll()
                        .requestMatchers("/api/user/sub/**").hasRole(Const.ROLE_ADMIN)
                        .anyRequest().hasAnyRole(Const.ROLE_DEFAULT,Const.ROLE_ADMIN)
                )
                .formLogin(conf -> conf
                        .loginProcessingUrl("/api/auth/login")
                        .failureHandler(this::handleProcess)
                        .successHandler(this::handleProcess)
                        .permitAll()
                )
                // v1.2 共享层：注册 OAuth2 登录入口（占位 Handler 由 Agent A 替换为真实 JWT 签发）。
                // SessionCreationPolicy 改为 IF_REQUIRED 后，OIDC 的 state/nonce 在登录流程中暂存于临时 session，
                // 但 JwtFilter 路径仍是 stateless（不主动创建 session）。
                .oauth2Login(conf -> conf
                        .successHandler(oidcSuccessHandler)
                        .failureHandler(oidcFailureHandler)
                )
                .logout(conf -> conf
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(this::onLogoutSuccess)
                )
                .exceptionHandling(conf -> conf
                        .accessDeniedHandler(this::handleProcess)
                        .authenticationEntryPoint(this::handleProcess)
                )
                .csrf(AbstractHttpConfigurer::disable)
                // P1-1：IF_REQUIRED 允许 OAuth2 state / 绑定意图临时 session，
                // 但 NullSecurityContextRepository 阻止 SecurityContext 被持久化到 session，
                // 杜绝 form/oauth2 登录后浏览器仅凭 JSESSIONID 访问受保护 API（绕过 JWT/API Token 契约）。
                .securityContext(ctx -> ctx
                        .securityContextRepository(new NullSecurityContextRepository()))
                .sessionManagement(conf -> conf
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .addFilterBefore(requestLogFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtFilter, RequestLogFilter.class)
                // v1.2 共享层：API Token 校验过滤器排在 JwtFilter 之后；命中 SecurityContext 即跳过，
                // 真实 HMAC-SHA256 校验由 Agent B 在 ApiTokenFilter 内落地。
                .addFilterAfter(apiTokenFilter, JwtFilter.class)
                .build();
    }

    /**
     * 将多种类型的Handler整合到同一个方法中，包含：
     * - 登录成功
     * - 登录失败
     * - 未登录拦截/无权限拦截
     * @param request 请求
     * @param response 响应
     * @param exceptionOrAuthentication 异常或是验证实体
     * @throws IOException 可能的异常
     */
    private void handleProcess(HttpServletRequest request,
                               HttpServletResponse response,
                               Object exceptionOrAuthentication) throws IOException {
        response.setContentType("application/json;charset=utf-8");
        PrintWriter writer = response.getWriter();
        if(exceptionOrAuthentication instanceof AccessDeniedException exception) {
            writer.write(RestBean
                    .forbidden(exception.getMessage()).asJsonString());
        } else if(exceptionOrAuthentication instanceof Exception exception) {
            writer.write(RestBean
                    .unauthorized(exception.getMessage()).asJsonString());
        } else if(exceptionOrAuthentication instanceof Authentication authentication){
            User user = (User) authentication.getPrincipal();
            Account account = service.findAccountByNameOrEmail(user.getUsername());
            String jwt = utils.createJwt(user, account.getUsername(), account.getId());
            if(jwt == null) {
                writer.write(RestBean.forbidden("登录验证频繁，请稍后再试").asJsonString());
            } else {
                AuthorizeVO vo = accountStructMapper.toAuthorizeVO(account);
                vo.setToken(jwt);
                vo.setExpire(utils.expireTime());
                writer.write(RestBean.success(vo).asJsonString());
            }
        }
    }

    /**
     * 退出登录处理，将对应的Jwt令牌列入黑名单不再使用
     * @param request 请求
     * @param response 响应
     * @param authentication 验证实体
     * @throws IOException 可能的异常
     */
    private void onLogoutSuccess(HttpServletRequest request,
                                 HttpServletResponse response,
                                 Authentication authentication) throws IOException {
        response.setContentType("application/json;charset=utf-8");
        PrintWriter writer = response.getWriter();
        String authorization = request.getHeader("Authorization");
        if(utils.invalidateJwt(authorization)) {
            writer.write(RestBean.success("退出登录成功").asJsonString());
            return;
        }
        writer.write(RestBean.failure(400, "退出登录失败").asJsonString());
    }
}
