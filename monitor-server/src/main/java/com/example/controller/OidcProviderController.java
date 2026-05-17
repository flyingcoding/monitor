package com.example.controller;

import com.example.entity.RestBean;
import com.example.entity.vo.request.OidcProviderCreateVO;
import com.example.entity.vo.request.OidcProviderUpdateVO;
import com.example.entity.vo.response.OidcProviderVO;
import com.example.service.OidcProviderService;
import com.example.service.PermissionService;
import com.example.utils.Const;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * OIDC Provider 管理 API（仅管理员）。
 *
 * <p>与 {@code NotificationChannelController} 同款鉴权模式：在每个方法首行用
 * {@link PermissionService#isAdmin(String)} 校验角色，非管理员返回 {@link RestBean#noPermission()}。
 * 不使用 {@code @PreAuthorize}，与现有 Controller 风格保持一致。
 *
 * <p>额外约束：拒绝以 API Token 鉴权的请求调用（即使持有 admin 角色的 token 也不可），
 * 保持 "token 不能管理 auth surface" 的一致性。与 {@code ApiTokenController} /
 * {@code OidcBindingController} 同款 {@code isApiTokenAuth} guard。
 */
@Slf4j
@RestController
@RequestMapping("/api/oidc/providers")
public class OidcProviderController {

    @Resource
    private OidcProviderService oidcProviderService;

    @Resource
    private PermissionService permissionService;

    @GetMapping
    public RestBean<List<OidcProviderVO>> list(HttpServletRequest request,
                                               @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (isApiTokenAuth(request)) {
            return RestBean.forbidden("不允许通过 API Token 管理 OIDC Provider");
        }
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        return RestBean.success(oidcProviderService.listAll());
    }

    @PostMapping
    public RestBean<OidcProviderVO> create(HttpServletRequest request,
                                           @RequestBody @Valid OidcProviderCreateVO vo,
                                           @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (isApiTokenAuth(request)) {
            return RestBean.forbidden("不允许通过 API Token 管理 OIDC Provider");
        }
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        return RestBean.success(oidcProviderService.create(vo));
    }

    @PutMapping("/{id}")
    public RestBean<OidcProviderVO> update(HttpServletRequest request,
                                           @PathVariable Long id,
                                           @RequestBody @Valid OidcProviderUpdateVO vo,
                                           @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (isApiTokenAuth(request)) {
            return RestBean.forbidden("不允许通过 API Token 管理 OIDC Provider");
        }
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        return RestBean.success(oidcProviderService.update(id, vo));
    }

    @DeleteMapping("/{id}")
    public RestBean<Void> delete(HttpServletRequest request,
                                 @PathVariable Long id,
                                 @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (isApiTokenAuth(request)) {
            return RestBean.forbidden("不允许通过 API Token 管理 OIDC Provider");
        }
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        if (!oidcProviderService.delete(id)) {
            return RestBean.failure(404, "Provider 不存在");
        }
        return RestBean.success();
    }

    private boolean isApiTokenAuth(HttpServletRequest request) {
        Object method = request.getAttribute(Const.ATTR_AUTH_METHOD);
        return Const.AUTH_METHOD_API_TOKEN.equals(method);
    }
}
