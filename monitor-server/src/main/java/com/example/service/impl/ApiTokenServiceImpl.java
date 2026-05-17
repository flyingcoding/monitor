package com.example.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.entity.dto.ApiToken;
import com.example.entity.vo.request.ApiTokenCreateVO;
import com.example.entity.vo.response.ApiTokenCreatedVO;
import com.example.entity.vo.response.ApiTokenVO;
import com.example.mapper.ApiTokenMapper;
import com.example.service.ApiTokenService;
import com.example.utils.ApiTokenUtils;
import com.example.utils.Const;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * API Token 服务实现（prd D5）。
 *
 * <h3>哈希与匹配</h3>
 * <p>{@link #validateAndResolve(String)} 通过 {@link ApiTokenUtils#hash(String)} 计算 HMAC-SHA256 后
 * 在 {@code token_hash} 唯一索引上做 O(1) 等值查询。命中后再调用
 * {@link ApiTokenUtils#constantTimeEquals(String, String)} 防御性兜底，
 * 即使后续重构改成 {@code IN (...)} 多哈希批量查也不会泄露字符级时序信息。
 *
 * <h3>使用记录节流</h3>
 * <p>{@link #recordUsage(long, String)} 使用 Caffeine 缓存 {@code tokenId → lastUpdatedEpochMs}，
 * 60 秒内的二次调用直接跳过 UPDATE。这避免每 API 请求一次写库（参考 prd R18 与
 * api-token-design.md §3.3）。实际 UPDATE 通过 {@link Async} 走 {@code alertTaskExecutor}
 * 虚拟线程池异步执行，不阻塞 ApiTokenFilter。
 */
@Slf4j
@Service
public class ApiTokenServiceImpl
        extends ServiceImpl<ApiTokenMapper, ApiToken>
        implements ApiTokenService {

    @Resource
    private ApiTokenUtils apiTokenUtils;

    /**
     * 节流缓存：tokenId → 最近一次更新 last_used_at 的 epoch ms。
     * <p>失效后允许重新更新；500 容量足够（一个账号通常 ≤10 个 token）。
     */
    private final Cache<Long, Long> recordUsageThrottle = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(2_000)
            .build();

    /**
     * 节流窗口：默认 60 秒。常量直接定义避免引入额外配置项。
     */
    private static final long RECORD_USAGE_THROTTLE_MS = TimeUnit.SECONDS.toMillis(60);

    @Override
    public ApiTokenCreatedVO create(int accountId, ApiTokenCreateVO vo) {
        String plain = apiTokenUtils.generatePlainToken();
        ApiToken entity = new ApiToken();
        entity.setAccountId(accountId);
        entity.setName(vo.getName());
        entity.setTokenHash(apiTokenUtils.hash(plain));
        entity.setPrefixTail(apiTokenUtils.buildPrefixTail(plain));
        entity.setScope(vo.getScope());
        entity.setExpiresAt(vo.getExpiresAt());
        entity.setCreatedAt(new Date());
        this.save(entity);
        log.info("API Token 创建 accountId={} tokenId={} name={} scope={}",
                accountId, entity.getId(), entity.getName(), entity.getScope());

        ApiTokenCreatedVO result = new ApiTokenCreatedVO();
        result.setToken(plain);
        result.setMeta(toVO(entity));
        return result;
    }

    @Override
    public List<ApiTokenVO> list(int accountId) {
        return this.list(new QueryWrapper<ApiToken>()
                .eq("account_id", accountId)
                .orderByDesc("created_at"))
                .stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public boolean delete(int accountId, long tokenId) {
        ApiToken existing = this.getById(tokenId);
        if (existing == null || existing.getAccountId() == null || existing.getAccountId() != accountId) {
            return false;
        }
        boolean removed = this.removeById(tokenId);
        if (removed) {
            log.info("API Token 删除 accountId={} tokenId={}", accountId, tokenId);
        }
        return removed;
    }

    /**
     * 旋转 token：删除旧 token 并以相同 name/scope/expiresAt 新建。
     *
     * <p>P2-4 修复：标注 {@link Transactional @Transactional(rollbackFor = Exception.class)}，
     * 保证 {@link #removeById(java.io.Serializable)} 与 {@link #create(int, ApiTokenCreateVO)}
     * 处于同一事务。{@link com.example.utils.ApiTokenUtils#hash(String)} 在密钥缺失时抛
     * {@link IllegalStateException}，默认 Spring 事务管理对 {@link RuntimeException} 子类自动回滚；
     * 显式 {@code rollbackFor = Exception.class} 用于兜底未来可能的检查型异常路径。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Optional<ApiTokenCreatedVO> rotate(int accountId, long tokenId) {
        ApiToken existing = this.getById(tokenId);
        if (existing == null || existing.getAccountId() == null || existing.getAccountId() != accountId) {
            return Optional.empty();
        }
        // 先复制旧 token 元数据（name / scope / expiresAt），再删除并重建
        ApiTokenCreateVO recreate = new ApiTokenCreateVO();
        recreate.setName(existing.getName());
        recreate.setScope(existing.getScope());
        recreate.setExpiresAt(existing.getExpiresAt());

        this.removeById(tokenId);
        log.info("API Token 旋转：删除旧 tokenId={} accountId={}", tokenId, accountId);
        return Optional.of(this.create(accountId, recreate));
    }

    @Override
    public Optional<ApiToken> validateAndResolve(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        if (!rawToken.startsWith(Const.API_TOKEN_PREFIX)) {
            return Optional.empty();
        }
        String expectedHash;
        try {
            expectedHash = apiTokenUtils.hash(rawToken);
        } catch (IllegalStateException | IllegalArgumentException e) {
            // 密钥未配置或参数非法 — 视为校验失败而不是抛 500
            log.warn("API Token 校验失败：{}", e.getMessage());
            return Optional.empty();
        }
        ApiToken record = this.getOne(new QueryWrapper<ApiToken>()
                .eq("token_hash", expectedHash), false);
        if (record == null) {
            return Optional.empty();
        }
        if (!apiTokenUtils.constantTimeEquals(record.getTokenHash(), expectedHash)) {
            // 防御性兜底：极端情况下索引匹配但字段值不严格相等
            return Optional.empty();
        }
        if (record.getExpiresAt() != null && record.getExpiresAt().before(new Date())) {
            return Optional.empty();
        }
        return Optional.of(record);
    }

    @Override
    @Async("alertTaskExecutor")
    public void recordUsage(long tokenId, String ip) {
        long now = System.currentTimeMillis();
        Long last = recordUsageThrottle.getIfPresent(tokenId);
        if (last != null && now - last < RECORD_USAGE_THROTTLE_MS) {
            return;
        }
        recordUsageThrottle.put(tokenId, now);
        try {
            ApiToken update = new ApiToken();
            update.setId(tokenId);
            update.setLastUsedAt(new Date(now));
            update.setLastUsedIp(truncateIp(ip));
            this.updateById(update);
        } catch (Exception e) {
            log.warn("API Token 使用记录更新失败 tokenId={} ip={} reason={}",
                    tokenId, ip, e.getMessage());
            // 失败不影响调用方；下次节流窗口外会再尝试
            recordUsageThrottle.invalidate(tokenId);
        }
    }

    /**
     * 截断 IP 到 64 字符（IPv6 + 可能的端口注释也不会超过这个长度）。
     *
     * @param ip 原始 IP
     * @return 截断后的 IP；null 时返回 null
     */
    private String truncateIp(String ip) {
        if (ip == null) return null;
        return ip.length() <= 64 ? ip : ip.substring(0, 64);
    }

    /**
     * 实体转视图 VO；剥离 token_hash 等敏感字段。
     */
    private ApiTokenVO toVO(ApiToken entity) {
        ApiTokenVO vo = new ApiTokenVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setPrefixTail(entity.getPrefixTail());
        vo.setScope(entity.getScope());
        vo.setExpiresAt(entity.getExpiresAt());
        vo.setLastUsedAt(entity.getLastUsedAt());
        vo.setLastUsedIp(entity.getLastUsedIp());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
