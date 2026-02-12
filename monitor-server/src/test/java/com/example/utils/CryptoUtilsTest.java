package com.example.utils;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

class CryptoUtilsTest {

    /**
     * 验证加密结果包含ENC前缀且可正确解密回原文。
     */
    @Test
    void encryptAndDecryptShouldBeSymmetric() {
        String base64Key = Base64.getEncoder()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        CryptoUtils cryptoUtils = new CryptoUtils(base64Key);
        String plain = "P@ssw0rd!";
        String encrypted = cryptoUtils.encrypt(plain);
        Assertions.assertTrue(encrypted.startsWith("ENC:"));
        Assertions.assertNotEquals(plain, encrypted);
        Assertions.assertEquals(plain, cryptoUtils.decrypt(encrypted));
    }

    /**
     * 验证历史明文数据在读取时保持兼容。
     */
    @Test
    void decryptShouldKeepPlaintextCompatible() {
        String base64Key = Base64.getEncoder()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        CryptoUtils cryptoUtils = new CryptoUtils(base64Key);
        Assertions.assertEquals("legacy-password", cryptoUtils.decrypt("legacy-password"));
    }

    /**
     * 验证以ENC:开头的明文密码不会被误判为已加密数据。
     */
    @Test
    void encryptShouldNotTreatPrefixedPlaintextAsCiphertext() {
        String base64Key = Base64.getEncoder()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        CryptoUtils cryptoUtils = new CryptoUtils(base64Key);
        String plain = "ENC:abc123";
        String encrypted = cryptoUtils.encrypt(plain);
        Assertions.assertNotEquals(plain, encrypted);
        Assertions.assertTrue(encrypted.startsWith("ENC:"));
        Assertions.assertEquals(plain, cryptoUtils.decrypt(encrypted));
    }

    /**
     * 验证非法ENC:前缀数据在解密时按明文兼容返回，避免异常中断。
     */
    @Test
    void decryptShouldKeepMalformedPrefixedPlaintextCompatible() {
        String base64Key = Base64.getEncoder()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        CryptoUtils cryptoUtils = new CryptoUtils(base64Key);
        Assertions.assertEquals("ENC:not-base64", cryptoUtils.decrypt("ENC:not-base64"));
    }
}
