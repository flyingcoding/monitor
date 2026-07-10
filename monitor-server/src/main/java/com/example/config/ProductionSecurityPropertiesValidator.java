package com.example.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.Set;

/**
 * 在生产环境启动前校验认证和加密密钥，避免应用带着示例值或缺失密钥运行。
 */
@Component
@Profile("prod")
public class ProductionSecurityPropertiesValidator {

    private static final int SECRET_KEY_BYTES = 32;
    private static final int MIN_JWT_KEY_LENGTH = 32;
    private static final Set<String> REJECTED_VALUES = Set.of(
            "monitor-key",
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
    );

    private final String jwtKey;
    private final String apiTokenHmacKey;
    private final String sshEncryptKey;

    /**
     * 注入生产环境的三类独立密钥。
     *
     * @param jwtKey JWT 签名密钥
     * @param apiTokenHmacKey API Token HMAC 密钥
     * @param sshEncryptKey SSH 密码 AES 密钥
     */
    public ProductionSecurityPropertiesValidator(
            @Value("${spring.security.jwt.key:}") String jwtKey,
            @Value("${monitor.api-token.hmac-key:}") String apiTokenHmacKey,
            @Value("${security.ssh.encrypt-key:}") String sshEncryptKey) {
        this.jwtKey = jwtKey;
        this.apiTokenHmacKey = apiTokenHmacKey;
        this.sshEncryptKey = sshEncryptKey;
    }

    /**
     * 在 Bean 初始化时拒绝不安全的生产配置。
     */
    @PostConstruct
    void validate() {
        validateConfiguration(jwtKey, apiTokenHmacKey, sshEncryptKey);
    }

    /**
     * 校验生产环境密钥的完整性、强度和相互独立性。
     *
     * @param jwtKey JWT 签名密钥
     * @param apiTokenHmacKey API Token HMAC 密钥（Base64 32 字节）
     * @param sshEncryptKey SSH AES 密钥（Base64 32 字节）
     */
    static void validateConfiguration(String jwtKey, String apiTokenHmacKey, String sshEncryptKey) {
        requireConfigured("spring.security.jwt.key", jwtKey);
        if (jwtKey.trim().length() < MIN_JWT_KEY_LENGTH) {
            throw new IllegalStateException("spring.security.jwt.key 在生产环境至少需要 32 个字符");
        }

        byte[] apiTokenKey = decode32ByteKey("monitor.api-token.hmac-key", apiTokenHmacKey);
        byte[] sshKey = decode32ByteKey("security.ssh.encrypt-key", sshEncryptKey);
        if (jwtKey.trim().equals(apiTokenHmacKey.trim()) || jwtKey.trim().equals(sshEncryptKey.trim())
                || MessageDigest.isEqual(apiTokenKey, sshKey)) {
            throw new IllegalStateException("JWT、API Token HMAC 与 SSH 加密密钥必须彼此不同");
        }
    }

    /**
     * 拒绝空值、模板占位符和仓库中历史示例密钥。
     *
     * @param propertyName 配置项名称
     * @param value 配置值
     */
    private static void requireConfigured(String propertyName, String value) {
        if (value == null || value.isBlank() || value.trim().startsWith("your_")
                || REJECTED_VALUES.contains(value.trim())) {
            throw new IllegalStateException(propertyName + " 未配置安全的生产密钥");
        }
    }

    /**
     * 解码并校验 Base64 格式的 32 字节密钥。
     *
     * @param propertyName 配置项名称
     * @param value Base64 编码密钥
     * @return 解码后的密钥字节
     */
    private static byte[] decode32ByteKey(String propertyName, String value) {
        requireConfigured(propertyName, value);
        try {
            byte[] decoded = Base64.getDecoder().decode(value.trim());
            if (decoded.length != SECRET_KEY_BYTES) {
                throw new IllegalStateException(propertyName + " 必须是 Base64 编码的 32 字节密钥");
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(propertyName + " 必须是合法的 Base64 密钥", e);
        }
    }
}
