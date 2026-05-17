package com.example.utils;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * API Token 工具：明文 token 生成 + HMAC-SHA256 哈希 + 恒定时间比对。
 *
 * <p>密钥来自 {@code monitor.api-token.hmac-key}（独立于 {@code JWT_KEY} 与 SSH 加密密钥，详见 prd D5）。
 * 密钥应为 base64 编码的随机字节（建议 32 字节），缺失时 {@link #verifyKeyConfigured} 会在 token 创建/校验路径上抛出
 * {@link IllegalStateException}，让运维明确感知配置缺失。
 *
 * <h3>密钥轮换</h3>
 * <p>本项目<b>不支持</b>密钥旋转。一旦更换 {@code monitor.api-token.hmac-key}，所有现有 token 的哈希会失配，
 * 用户需要重新生成。原因：HMAC-SHA256 的密钥是确定性参与运算的，且 token 哈希只存一份；不像 JWT 可以重签发。
 * 文档已在 prd D5 明示。
 *
 * <h3>明文 token 格式</h3>
 * <pre>
 *   mtk_&lt;32 chars base62&gt;     总长度 36 字符，约 190 bit 熵
 * </pre>
 *
 * 使用 {@link SecureRandom} 与 {@link #BASE62} 字符集，避免 URL/JSON 转义问题。
 */
@Slf4j
@Component
public class ApiTokenUtils {

    /**
     * base62 字符集（[A-Za-z0-9]）。
     */
    private static final char[] BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    /**
     * 主体长度（不含前缀 {@code mtk_}），32 字符 base62 ≈ 190 bit 熵。
     */
    private static final int RAW_TOKEN_LENGTH = 32;

    private static final String HMAC_ALGO = "HmacSHA256";

    private final SecureRandom secureRandom = new SecureRandom();

    private byte[] hmacKeyBytes;

    @Value("${monitor.api-token.hmac-key:}")
    private String hmacKeyConfig;

    /**
     * 启动时解析 base64 密钥；空值时不抛出，留到首次实际使用时才报错，便于本地起 dev 没填的场景。
     */
    @PostConstruct
    void init() {
        if (hmacKeyConfig == null || hmacKeyConfig.isBlank()) {
            hmacKeyBytes = null;
            log.warn("monitor.api-token.hmac-key 未配置；API Token 功能将在使用时报错。请设置 API_TOKEN_HMAC_KEY 环境变量。");
            return;
        }
        try {
            hmacKeyBytes = Base64.getDecoder().decode(hmacKeyConfig);
            if (hmacKeyBytes.length < 16) {
                log.warn("monitor.api-token.hmac-key 解码后仅 {} 字节，建议 ≥32 字节以达到 HMAC-SHA256 推荐强度",
                        hmacKeyBytes.length);
            }
        } catch (IllegalArgumentException e) {
            hmacKeyBytes = null;
            log.error("monitor.api-token.hmac-key 不是合法的 Base64", e);
        }
    }

    /**
     * 生成一个完整明文 token，例如 {@code mtk_aB3D...xY9z}。每次调用返回独立随机值。
     *
     * @return 明文 token
     */
    public String generatePlainToken() {
        char[] body = new char[RAW_TOKEN_LENGTH];
        for (int i = 0; i < RAW_TOKEN_LENGTH; i++) {
            body[i] = BASE62[secureRandom.nextInt(BASE62.length)];
        }
        return Const.API_TOKEN_PREFIX + new String(body);
    }

    /**
     * 计算 token 的 HMAC-SHA256 摘要（URL-safe base64，无填充）。
     *
     * @param plainToken 明文 token；若为 null/blank 抛 {@link IllegalArgumentException}
     * @return URL-safe base64 编码的 32 字节摘要（长度约 43 字符）
     */
    public String hash(String plainToken) {
        if (plainToken == null || plainToken.isBlank()) {
            throw new IllegalArgumentException("plainToken 不能为空");
        }
        verifyKeyConfigured();
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(hmacKeyBytes, HMAC_ALGO));
            byte[] digest = mac.doFinal(plainToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("HmacSHA256 不可用", e);
        } catch (java.security.InvalidKeyException e) {
            throw new IllegalStateException("HMAC 密钥非法", e);
        }
    }

    /**
     * 恒定时间比较两个哈希字符串，避免按字符长度泄露信息（时序攻击防护）。
     *
     * @param a hash a，可能为 null
     * @param b hash b，可能为 null
     * @return 两者非 null 且字节级完全相等时返回 true
     */
    public boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aBytes, bBytes);
    }

    /**
     * 生成 "mtk_xxxx…abcd" 形式的展示字段（前缀 + 前 4 + … + 末 4）。
     *
     * <p>如果 token 主体长度不足 8（理论上不可能，{@link #generatePlainToken} 固定 32），降级为
     * "mtk_<完整主体>"。
     *
     * @param plainToken 明文 token
     * @return 展示字段
     */
    public String buildPrefixTail(String plainToken) {
        if (plainToken == null) return null;
        if (!plainToken.startsWith(Const.API_TOKEN_PREFIX)) {
            return plainToken;
        }
        String body = plainToken.substring(Const.API_TOKEN_PREFIX.length());
        if (body.length() < 8) {
            return plainToken;
        }
        String head = body.substring(0, 4);
        String tail = body.substring(body.length() - 4);
        return Const.API_TOKEN_PREFIX + head + "…" + tail;
    }

    /**
     * 检查 HMAC 密钥是否已加载，否则抛 {@link IllegalStateException}。
     */
    private void verifyKeyConfigured() {
        if (hmacKeyBytes == null || hmacKeyBytes.length == 0) {
            throw new IllegalStateException("API Token HMAC 密钥未配置（monitor.api-token.hmac-key）");
        }
    }
}
