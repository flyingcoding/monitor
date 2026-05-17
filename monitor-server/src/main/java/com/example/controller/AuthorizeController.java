package com.example.controller;

import com.example.entity.RestBean;
import com.example.entity.dto.Account;
import com.example.entity.vo.request.ConfirmResetVO;
import com.example.entity.vo.request.EmailResetVO;
import com.example.entity.vo.response.AuthorizeVO;
import com.example.mapper.struct.AccountStructMapper;
import com.example.service.AccountService;
import com.example.utils.Const;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.function.Supplier;

/**
 * 用于验证相关Controller包含用户的注册、重置密码等操作
 */
@Validated
@RestController
@RequestMapping("/api/auth")
@Tag(name = "登录校验相关", description = "包括用户登录、注册、验证码请求等操作。")
public class AuthorizeController {

    @Resource
    AccountService accountService;

    @Resource
    AccountStructMapper accountStructMapper;

    /**
     * 请求邮件验证码
     * @param email 请求邮件
     * @param type 类型
     * @param request 请求
     * @return 是否请求成功
     */
    @GetMapping("/ask-code")
    @Operation(summary = "请求邮件验证码")
    public RestBean<Void> askVerifyCode(@RequestParam @Email String email,
                                        @RequestParam @Pattern(regexp = "(reset|modify)")  String type,
                                        HttpServletRequest request){
        return this.messageHandle(() ->
                accountService.registerEmailVerifyCode(type, String.valueOf(email), request.getRemoteAddr()));
    }


    /**
     * 执行密码重置确认，检查验证码是否正确
     * @param vo 密码重置信息
     * @return 是否操作成功
     */
    @PostMapping("/reset-confirm")
    @Operation(summary = "密码重置确认")
    public RestBean<Void> resetConfirm(@RequestBody @Valid ConfirmResetVO vo){
        return this.messageHandle(() -> accountService.resetConfirm(vo));
    }

    /**
     * 执行密码重置操作
     * @param vo 密码重置信息
     * @return 是否操作成功
     */
    @PostMapping("/reset-password")
    @Operation(summary = "密码重置操作")
    public RestBean<Void> resetPassword(@RequestBody @Valid EmailResetVO vo){
        return this.messageHandle(() ->
                accountService.resetEmailAccountPassword(vo));
    }

    /**
     * 获取当前登录用户信息。
     *
     * <p>P2-1：OIDC 登录回调后前端只拿到 JWT，没有 role/username/email，
     * 立即调用本接口刷新 store；JwtFilter 已经把 accountId 写入请求属性，
     * 这里直接据此查账号回填 VO。
     *
     * <p>不返回 token 字段（前端已持有）；如 token 失效会被 JwtFilter 401 拦在外层。
     *
     * @param accountId JwtFilter 写入的当前账号 ID
     * @return 当前账号的 username / role / email
     */
    @GetMapping("/me")
    @Operation(summary = "获取当前登录用户信息")
    public RestBean<AuthorizeVO> currentUser(
            @RequestAttribute(value = Const.ATTR_USER_ID, required = false) Integer accountId) {
        // /api/auth/** permitAll，因此 JwtFilter 未写入 attr 即视为未登录
        if (accountId == null) {
            return RestBean.unauthorized("未登录");
        }
        Account account = accountService.getById(accountId);
        if (account == null) {
            return RestBean.failure(404, "账号不存在");
        }
        AuthorizeVO vo = accountStructMapper.toAuthorizeVO(account);
        return RestBean.success(vo);
    }

    /**
     * 针对于返回值为String作为错误信息的方法进行统一处理
     * @param action 具体操作
     * @return 响应结果
     * @param <T> 响应结果类型
     */
    private <T> RestBean<T> messageHandle(Supplier<String> action){
        String message = action.get();
        if(message == null)
            return RestBean.success();
        else
            return RestBean.failure(400, message);
    }
}
