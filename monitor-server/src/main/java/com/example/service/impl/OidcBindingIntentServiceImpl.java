package com.example.service.impl;

import com.example.service.OidcBindingIntentService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Redis 持久化的 OIDC 绑定意图 token 服务（P2-2 修复实现）。
 *
 * <p>Key 设计：{@code oidc:bind:intent:<token>} → accountId 字符串。
 * 5min TTL；{@link #consume(String)} 通过 {@code DEL} 原子删除，避免并发重复消费。
 *
 * <p>Token 设计：32 字符 base62（约 190 bit 熵），与 API Token 同源熵；不公开，仅在前端 -> 后端
 * 一次性传递，不进入日志（{@link com.example.filter.RequestLogFilter} 字段名匹配
 * "token" 会自动脱敏）。
 */
@Slf4j
@Service
public class OidcBindingIntentServiceImpl implements OidcBindingIntentService {

    /**
     * Redis Key 前缀。
     */
    private static final String KEY_PREFIX = "oidc:bind:intent:";

    /**
     * Intent token 有效期（分钟）。5min 足够用户完成 OAuth 跳转 + IdP 同意流程。
     */
    private static final long TTL_MINUTES = 5;

    /**
     * Token 字符表：base62 子集，避免歧义字符。
     */
    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    /**
     * Token 长度。
     */
    private static final int TOKEN_LENGTH = 32;

    private final SecureRandom random = new SecureRandom();

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public String issue(int accountId) {
        String token = generateToken();
        stringRedisTemplate.opsForValue().set(KEY_PREFIX + token,
                String.valueOf(accountId),
                TTL_MINUTES, TimeUnit.MINUTES);
        log.info("OIDC binding intent issued accountId={}", accountId);
        return token;
    }

    @Override
    public Optional<Integer> consume(String intentToken) {
        if (intentToken == null || intentToken.isBlank()) {
            return Optional.empty();
        }
        String key = KEY_PREFIX + intentToken;
        String raw = stringRedisTemplate.opsForValue().get(key);
        if (raw == null) {
            return Optional.empty();
        }
        // 立即删除，防止并发重复消费
        Boolean deleted = stringRedisTemplate.delete(key);
        if (Boolean.FALSE.equals(deleted)) {
            // 已被其他线程消费
            log.warn("OIDC binding intent token race: consumed by another thread");
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            log.warn("OIDC binding intent token Redis value malformed: {}", raw);
            return Optional.empty();
        }
    }

    private String generateToken() {
        char[] buf = new char[TOKEN_LENGTH];
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            buf[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(buf);
    }
}
