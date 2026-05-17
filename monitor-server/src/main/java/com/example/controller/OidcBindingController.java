package com.example.controller;

import com.example.entity.RestBean;
import com.example.entity.vo.response.OidcBindingVO;
import com.example.service.AccountOidcBindingService;
import com.example.utils.Const;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 个人 OIDC 绑定管理 API（需登录）。
 */
@Slf4j
@RestController
@RequestMapping("/api/oidc/bindings")
public class OidcBindingController {

    @Resource
    private AccountOidcBindingService accountOidcBindingService;

    /**
     * 列出当前账号的全部 OIDC 绑定。
     */
    @GetMapping
    public RestBean<List<OidcBindingVO>> list(@RequestAttribute(Const.ATTR_USER_ID) int userId) {
        return RestBean.success(accountOidcBindingService.listByAccount(userId));
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
    public RestBean<Void> unbind(@PathVariable String provider,
                                 @RequestAttribute(Const.ATTR_USER_ID) int userId) {
        AccountOidcBindingService.UnbindResult result = accountOidcBindingService.unbind(userId, provider);
        return switch (result) {
            case OK -> RestBean.success();
            case BINDING_NOT_FOUND -> RestBean.failure(400, "未找到该 Provider 的绑定");
            case LAST_LOGIN_METHOD -> RestBean.failure(400, "解绑后将失去登录方式，请先设置密码或绑定其他 Provider");
        };
    }
}
