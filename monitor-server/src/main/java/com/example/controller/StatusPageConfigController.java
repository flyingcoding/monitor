package com.example.controller;

import com.example.entity.RestBean;
import com.example.entity.vo.request.StatusPageConfigUpdateVO;
import com.example.entity.vo.response.StatusPageConfigVO;
import com.example.service.PermissionService;
import com.example.service.StatusPageService;
import com.example.utils.Const;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开状态页配置管理接口（PRD R22 / R27，仅管理员）。
 *
 * <p>路径仍在 {@code /api/status/**} 范围内（已 {@code permitAll}）。鉴权依赖
 * {@link com.example.filter.JwtFilter} 写入的 {@code ATTR_USER_ROLE}：
 * <ul>
 *   <li>无 Authorization 头 → JwtFilter 不写属性，本控制器返回 {@code unauthorized}。</li>
 *   <li>有效 JWT → 检查角色；非管理员返回 {@link RestBean#noPermission()}。</li>
 *   <li>无效 JWT → JwtFilter 不抛错（路径 permitAll），同样进入本控制器，最终走 {@code unauthorized}。</li>
 * </ul>
 *
 * <p>这一双重防护与项目惯例 {@code OidcProviderController} / {@code NotificationChannelController} 一致：
 * 没有依赖 {@code @PreAuthorize}，避免引入 {@code @EnableMethodSecurity} 但无 hook 消费的尴尬。
 */
@Slf4j
@RestController
@RequestMapping("/api/status/config")
public class StatusPageConfigController {

    @Resource
    private StatusPageService statusPageService;

    @Resource
    private PermissionService permissionService;

    @GetMapping
    public RestBean<StatusPageConfigVO> getConfig(
            @RequestAttribute(value = Const.ATTR_USER_ROLE, required = false) String userRole) {
        if (userRole == null) {
            return RestBean.unauthorized("未登录");
        }
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        return RestBean.success(statusPageService.getAdminConfig());
    }

    @PutMapping
    public RestBean<StatusPageConfigVO> updateConfig(
            @RequestAttribute(value = Const.ATTR_USER_ROLE, required = false) String userRole,
            @RequestBody @Valid StatusPageConfigUpdateVO vo) {
        if (userRole == null) {
            return RestBean.unauthorized("未登录");
        }
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        return RestBean.success(statusPageService.updateConfig(vo));
    }
}
