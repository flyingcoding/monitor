package com.example.controller;

import com.example.entity.RestBean;
import com.example.entity.vo.request.ApiTokenCreateVO;
import com.example.entity.vo.response.ApiTokenCreatedVO;
import com.example.entity.vo.response.ApiTokenVO;
import com.example.service.ApiTokenService;
import com.example.utils.Const;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * REST API Token 管理接口（v1.2 prd R17）。
 *
 * <p>所有接口需要 JWT 登录才能调用；不允许使用 API Token 自身管理 token（防止 "token 派生 token"）。
 * 路径常量：{@code /api/tokens/*}。
 *
 * <h3>明文 token 只在创建/旋转时返回一次</h3>
 * <p>{@link ApiTokenCreatedVO#getToken()} 是用户能看到完整明文的唯一时机；前端必须立刻展示并提示复制。
 * 后续 {@link #list(int)} 只返回 {@code prefix_tail}（"mtk_xxxx…abcd"）。
 */
@Slf4j
@RestController
@RequestMapping("/api/tokens")
public class ApiTokenController {

    @Resource
    private ApiTokenService apiTokenService;

    /**
     * 创建新的 API Token。
     *
     * <p>拒绝以 API Token 鉴权的请求调用（{@code ATTR_AUTH_METHOD == AUTH_METHOD_API_TOKEN}），
     * 即不允许 token 生成更多 token，规避横向扩权风险。
     *
     * @param request  Servlet 请求（用于读取 ATTR_AUTH_METHOD）
     * @param accountId 当前账号 ID（来自 JwtFilter）
     * @param vo       创建请求
     * @return 一次性明文 token + 元数据
     */
    @PostMapping
    public RestBean<ApiTokenCreatedVO> create(HttpServletRequest request,
                                              @RequestAttribute(Const.ATTR_USER_ID) int accountId,
                                              @RequestBody @Valid ApiTokenCreateVO vo) {
        if (isApiTokenAuth(request)) {
            return RestBean.forbidden("不允许通过 API Token 管理 API Token");
        }
        ApiTokenCreatedVO created = apiTokenService.create(accountId, vo);
        return RestBean.success(created);
    }

    /**
     * 列出当前账号的全部 API Token（不含明文 / hash）。
     *
     * <p>P2-1 修复：与 {@link #create} 一致，拒绝以 API Token 鉴权的请求调用，防止 readwrite token
     * 自行枚举/操作账号下的其他 token（research/api-token-design.md §scope "tokens can't manage tokens"）。
     *
     * @param request   Servlet 请求
     * @param accountId 当前账号 ID
     * @return token 元数据列表
     */
    @GetMapping
    public RestBean<List<ApiTokenVO>> list(HttpServletRequest request,
                                           @RequestAttribute(Const.ATTR_USER_ID) int accountId) {
        if (isApiTokenAuth(request)) {
            return RestBean.forbidden("不允许通过 API Token 管理 API Token");
        }
        return RestBean.success(apiTokenService.list(accountId));
    }

    /**
     * 删除当前账号下指定 token。
     *
     * <p>P2-1 修复：同 {@link #list} 拒绝 API Token 自管。
     *
     * @param request   Servlet 请求
     * @param accountId 当前账号 ID
     * @param id        token 主键
     * @return 删除结果；不存在时返回 404
     */
    @DeleteMapping("/{id}")
    public RestBean<Void> delete(HttpServletRequest request,
                                 @RequestAttribute(Const.ATTR_USER_ID) int accountId,
                                 @PathVariable Long id) {
        if (isApiTokenAuth(request)) {
            return RestBean.forbidden("不允许通过 API Token 管理 API Token");
        }
        boolean removed = apiTokenService.delete(accountId, id);
        if (!removed) {
            return RestBean.failure(404, "API Token 不存在");
        }
        return RestBean.success();
    }

    /**
     * 旋转 token：删除旧 token 并以相同 name/scope/expiresAt 新建。
     *
     * <p>同样拒绝 API Token 自身调用，原因与 {@link #create} 相同。
     *
     * @param request   Servlet 请求
     * @param accountId 当前账号 ID
     * @param id        token 主键
     * @return 新 token 元数据 + 一次性明文；不存在返回 404
     */
    @PostMapping("/{id}/rotate")
    public RestBean<ApiTokenCreatedVO> rotate(HttpServletRequest request,
                                              @RequestAttribute(Const.ATTR_USER_ID) int accountId,
                                              @PathVariable Long id) {
        if (isApiTokenAuth(request)) {
            return RestBean.forbidden("不允许通过 API Token 管理 API Token");
        }
        Optional<ApiTokenCreatedVO> result = apiTokenService.rotate(accountId, id);
        return result.map(RestBean::success)
                .orElseGet(() -> RestBean.failure(404, "API Token 不存在"));
    }

    /**
     * 判断当前请求是否通过 API Token 鉴权。
     *
     * @param request Servlet 请求
     * @return 是 API Token 时返回 true
     */
    private boolean isApiTokenAuth(HttpServletRequest request) {
        Object method = request.getAttribute(Const.ATTR_AUTH_METHOD);
        return Const.AUTH_METHOD_API_TOKEN.equals(method);
    }
}
