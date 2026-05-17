package com.example.controller;

import com.example.config.security.oidc.OidcProperties;
import com.example.entity.RestBean;
import com.example.entity.vo.response.OidcProviderPublicVO;
import com.example.service.OidcProviderService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;

/**
 * 公开 OIDC Provider 列表（登录页渲染 OAuth 按钮用）。
 *
 * <p>路径 {@code /api/oidc/providers/public} 已在 {@code SecurityConfiguration.permitAll} 中放行，
 * 未登录可访问。{@code monitor.oidc.enabled=false} 时直接返回空列表，登录页不渲染按钮。
 */
@Slf4j
@RestController
@RequestMapping("/api/oidc/providers")
public class OidcProviderPublicController {

    @Resource
    private OidcProviderService oidcProviderService;

    @Resource
    private OidcProperties oidcProperties;

    @GetMapping("/public")
    public RestBean<List<OidcProviderPublicVO>> publicList() {
        if (!oidcProperties.isEnabled()) {
            return RestBean.success(Collections.emptyList());
        }
        return RestBean.success(oidcProviderService.listEnabledPublic());
    }
}
