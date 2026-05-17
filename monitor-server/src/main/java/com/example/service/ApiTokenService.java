package com.example.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.entity.dto.ApiToken;
import com.example.entity.vo.request.ApiTokenCreateVO;
import com.example.entity.vo.response.ApiTokenCreatedVO;
import com.example.entity.vo.response.ApiTokenVO;

import java.util.List;
import java.util.Optional;

/**
 * API Token 服务（prd D5）。
 *
 * <p>所有 token 操作都以 {@code accountId} 为主体范围；查询/删除时若不属于该账号，返回 404 / 空。
 */
public interface ApiTokenService extends IService<ApiToken> {

    /**
     * 为指定账号生成一个新的 API Token。
     *
     * @param accountId 当前账号 ID
     * @param vo        创建请求（含 name / scope / 可选 expiresAt）
     * @return 创建结果，包含一次性明文 token 与元数据
     */
    ApiTokenCreatedVO create(int accountId, ApiTokenCreateVO vo);

    /**
     * 列出指定账号的全部 API Token（不含明文 / hash）。
     *
     * @param accountId 当前账号 ID
     * @return token 元数据列表，按 created_at 倒序
     */
    List<ApiTokenVO> list(int accountId);

    /**
     * 删除指定账号下的某个 token。
     *
     * @param accountId 当前账号 ID
     * @param tokenId   token 主键
     * @return true 表示成功删除；false 表示不存在或不属于该账号
     */
    boolean delete(int accountId, long tokenId);

    /**
     * 旋转 token：删除旧 token，按相同 name / scope / expiresAt 生成新 token。原子操作（删除后立即创建）。
     *
     * @param accountId 当前账号 ID
     * @param tokenId   旧 token 主键
     * @return 新 token 元数据 + 一次性明文；不存在返回 {@code Optional.empty()}
     */
    Optional<ApiTokenCreatedVO> rotate(int accountId, long tokenId);

    /**
     * 校验明文 token 并解析对应 {@link ApiToken} 记录。
     *
     * <p>过程：
     * <ol>
     *   <li>明文为空或前缀非法 → 返回空；</li>
     *   <li>计算 HMAC-SHA256 哈希 → 查 {@code token_hash} 唯一索引；</li>
     *   <li>命中后校验 {@code expires_at IS NULL OR expires_at &gt; NOW()}；</li>
     *   <li>使用恒定时间比对验证 hash 严格相等（防御性兜底，索引命中已隐含相等）。</li>
     * </ol>
     *
     * @param rawToken 客户端提交的 Bearer 字符串去除 "Bearer " 后的明文
     * @return 命中且未过期的记录；其它情况返回 {@code Optional.empty()}
     */
    Optional<ApiToken> validateAndResolve(String rawToken);

    /**
     * 异步记录一次成功鉴权（更新 {@code last_used_at} / {@code last_used_ip}）。
     *
     * <p>同一 tokenId 60 秒内只触发一次实际 UPDATE；调用方不感知。
     *
     * @param tokenId token 主键
     * @param ip      客户端 IP，截断到 {@code VARCHAR(64)}
     */
    void recordUsage(long tokenId, String ip);
}
