package com.example.controller;

import com.example.entity.RestBean;
import com.example.entity.vo.request.ChangePasswordVO;
import com.example.entity.vo.request.CreateSubAccountVO;
import com.example.entity.vo.request.ModifyEmailVO;
import com.example.entity.vo.response.SubAccountVO;
import com.example.service.AccountService;
import com.example.service.PermissionService;
import com.example.utils.Const;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @program: monitor
 * @description: 用户相关接口
 * @author: 王贝强
 * @create: 2024-07-24 17:05
 */
@RestController
@RequestMapping("/api/user")
public class UserController {
    @Resource
    AccountService service;

    @Resource
    PermissionService permissionService;

    @PostMapping("/change-password")
    public RestBean<Void> changePassword(@RequestBody @Valid ChangePasswordVO vo,
                                         @RequestAttribute(Const.ATTR_USER_ID) int clientId){
        return service.changePassword(clientId,vo.getPassword(), vo.getNew_password()) ?
                RestBean.success() : RestBean.failure(401,"原始密码输入错误");
    }
    @PostMapping("/modify-email")
    public RestBean<Void> modifyEmail(@RequestAttribute(Const.ATTR_USER_ID) int userId,
                                      @RequestBody @Valid ModifyEmailVO vo){
        String result = service.modifyEmail(userId, vo);
        return result==null ? RestBean.success() :RestBean.failure(401,result);
    }
    /**
     * 创建普通子账户，仅允许 JWT 登录的管理员操作。
     *
     * @param request 当前 HTTP 请求
     * @param vo 子账户创建参数
     * @param userRole 当前用户角色
     * @return 操作结果
     */
    @PostMapping("/sub/create")
    public RestBean<Void> createSubAccount(HttpServletRequest request,
                                           @RequestBody @Valid CreateSubAccountVO vo,
                                           @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (isApiTokenAuth(request)) {
            return RestBean.forbidden("不允许通过 API Token 管理子账户");
        }
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        service.createSubAccount(vo);
        return RestBean.success();
    }

    /**
     * 删除普通子账户及其 API Token、OIDC 绑定。
     *
     * @param request 当前 HTTP 请求
     * @param uid 待删除子账户 ID
     * @param userId 当前登录账号 ID
     * @param userRole 当前用户角色
     * @return 操作结果
     */
    @DeleteMapping("/sub/{uid}")
    public RestBean<Void> deleteSubAccount(HttpServletRequest request,
                                           @PathVariable int uid,
                                           @RequestAttribute(Const.ATTR_USER_ID) int userId,
                                           @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (isApiTokenAuth(request)) {
            return RestBean.forbidden("不允许通过 API Token 管理子账户");
        }
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        if(uid == userId)
            return RestBean.failure(401, "非法参数");
        return service.deleteSubAccount(uid)
                ? RestBean.success()
                : RestBean.failure(404, "子账户不存在或不可删除");
    }

    /**
     * 查询全部子账户，仅允许 JWT 登录的管理员操作。
     *
     * @param request 当前 HTTP 请求
     * @param userRole 当前用户角色
     * @return 子账户列表
     */
    @GetMapping("/sub/list")
    public RestBean<List<SubAccountVO>> subAccountList(HttpServletRequest request,
                                                        @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (isApiTokenAuth(request)) {
            return RestBean.forbidden("不允许通过 API Token 管理子账户");
        }
        if (!permissionService.isAdmin(userRole)) {
            return RestBean.noPermission();
        }
        return RestBean.success(service.listSubAccount());
    }

    /**
     * 判断当前请求是否以 API Token 完成鉴权。
     *
     * @param request 当前 HTTP 请求
     * @return API Token 鉴权时返回 true
     */
    private boolean isApiTokenAuth(HttpServletRequest request) {
        return Const.AUTH_METHOD_API_TOKEN.equals(request.getAttribute(Const.ATTR_AUTH_METHOD));
    }
}
