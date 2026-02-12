package com.example.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 提供SSH密码的AES-256-GCM加解密能力，并兼容历史明文数据读取。
 */
@Component
public class CryptoUtils {

    private static final String PREFIX = "ENC:";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final int AES256_KEY_LENGTH = 32;

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 通过Base64密钥初始化加解密工具。
     *
     * @param base64Key Base64编码的32字节密钥
     */
    public CryptoUtils(@Value("${security.ssh.encrypt-key}") String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != AES256_KEY_LENGTH) {
            throw new IllegalArgumentException("security.ssh.encrypt-key 需为32字节AES-256密钥（Base64编码）");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * 加密明文密码并附加ENC前缀。
     *
     * @param plaintext 明文密码
     * @return 加密结果
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        if (this.isEncryptedByCurrentKey(plaintext)) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SSH密码加密失败", e);
        }
    }

    /**
     * 解密密文密码；若为历史明文则原样返回。
     *
     * @param ciphertext 密文或明文
     * @return 明文密码
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty() || !ciphertext.startsWith(PREFIX)) {
            return ciphertext;
        }
        if (!this.hasValidEncryptedPayload(ciphertext)) {
            return ciphertext;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext.substring(PREFIX.length()));
            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
            byte[] encrypted = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("SSH密码解密失败", e);
        }
    }

    /**
     * 判断字符串是否为当前密钥可成功解密的密文。
     *
     * @param value 待判断字符串
     * @return 可解密时返回true
     */
    private boolean isEncryptedByCurrentKey(String value) {
        if (!this.hasValidEncryptedPayload(value)) {
            return false;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
            byte[] encrypted = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            cipher.doFinal(encrypted);
            return true;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 校验 ENC: 前缀字符串是否满足最小密文结构（Base64 + IV + 密文）。
     *
     * @param value 待校验字符串
     * @return 满足最小结构时返回true
     */
    private boolean hasValidEncryptedPayload(String value) {
        if (value == null || !value.startsWith(PREFIX)) {
            return false;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            return combined.length > GCM_IV_LENGTH;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
