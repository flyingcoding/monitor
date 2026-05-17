package com.example.service;

import java.util.Optional;

/**
 * OIDC "绑定意图" Token 服务（P2-2 修复）。
 *
 * <p>解决"已登录账号点击绑定新 Provider"流程中 SuccessHandler 无法可靠拿到当前 accountId 的问题：
 * <ul>
 *   <li>前端不能直接 {@code window.location.href = '/oauth2/authorization/<name>'}，因为
 *       浏览器导航不会带上 localStorage 的 JWT；</li>
 *   <li>JwtFilter 是 stateless，不会写 HTTP session；</li>
 *   <li>因此显式生成"intent token"：账号在已登录态下通过 JWT 申请，落 Redis 5min；</li>
 *   <li>OAuth start 路径以 intent token 为凭据回填 session；SuccessHandler 读取 intent
 *       关联的 accountId，完成绑定。</li>
 * </ul>
 *
 * <p>不重复发：intent token 一次性，consume 后立即从 Redis 删除，防止他人猜测复用。
 */
public interface OidcBindingIntentService {

    /**
     * 生成绑定意图 token 并存入 Redis（5min TTL，账号锁定）。
     *
     * @param accountId 当前账号 ID
     * @return 不透明的 intent token（≥32 字符随机 base62）
     */
    String issue(int accountId);

    /**
     * 校验并消费 intent token：返回关联的 accountId 并立刻删除 Redis 条目。
     *
     * <p>消费后再次调用同一 token 返回空，防止单 token 多账号绑定漏洞。
     *
     * @param intentToken 前端透传的 intent token
     * @return 关联的 accountId；token 不存在 / 已消费 / 已过期时返回 empty
     */
    Optional<Integer> consume(String intentToken);
}
