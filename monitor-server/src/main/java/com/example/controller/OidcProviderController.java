package com.example.controller;

import com.example.entity.RestBean;
import com.example.entity.vo.request.OidcProviderCreateVO;
import com.example.entity.vo.request.OidcProviderUpdateVO;
import com.example.entity.vo.response.OidcProviderVO;
import com.example.service.OidcProviderService;
import com.example.service.PermissionService;
import com.example.utils.Const;
import jakarta.annotation.Resource;
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
    public RestBean<List<OidcProviderVO>> list(@RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        return RestBean.success(oidcProviderService.listAll());
    }

    @PostMapping
    public RestBean<OidcProviderVO> create(@RequestBody @Valid OidcProviderCreateVO vo,
                                           @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        return RestBean.success(oidcProviderService.create(vo));
    }

    @PutMapping("/{id}")
    public RestBean<OidcProviderVO> update(@PathVariable Long id,
                                           @RequestBody @Valid OidcProviderUpdateVO vo,
                                           @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        return RestBean.success(oidcProviderService.update(id, vo));
    }

    @DeleteMapping("/{id}")
    public RestBean<Void> delete(@PathVariable Long id,
                                 @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        if (!oidcProviderService.delete(id)) {
            return RestBean.failure(404, "Provider 不存在");
        }
        return RestBean.success();
    }
}
