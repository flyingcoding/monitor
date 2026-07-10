package com.example.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.stream.IntStream;

/**
 * {@link ProductionSecurityPropertiesValidator} 的生产密钥校验测试。
 */
class ProductionSecurityPropertiesValidatorTest {

    /**
     * 三个独立的强密钥应通过生产环境校验。
     */
    @Test
    void shouldAcceptIndependentValidKeys() {
        Assertions.assertDoesNotThrow(() -> ProductionSecurityPropertiesValidator.validateConfiguration(
                "jwt-signing-key-that-is-longer-than-32-characters",
                base64FilledWith((byte) 1),
                base64FilledWith((byte) 2)
        ));
    }

    /**
     * 空值、模板占位符和已知示例值必须阻止生产环境启动。
     */
    @Test
    void shouldRejectMissingPlaceholderAndHistoricalExampleValues() {
        Assertions.assertThrows(IllegalStateException.class,
                () -> ProductionSecurityPropertiesValidator.validateConfiguration(
                        "", base64FilledWith((byte) 1), base64FilledWith((byte) 2)));
        Assertions.assertThrows(IllegalStateException.class,
                () -> ProductionSecurityPropertiesValidator.validateConfiguration(
                        "jwt-signing-key-that-is-longer-than-32-characters",
                        "your_api_token_hmac_key", base64FilledWith((byte) 2)));
        Assertions.assertThrows(IllegalStateException.class,
                () -> ProductionSecurityPropertiesValidator.validateConfiguration(
                        "jwt-signing-key-that-is-longer-than-32-characters",
                        base64FilledWith((byte) 1), "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="));
    }

    /**
     * API Token 与 SSH 复用同一底层密钥或长度不正确时必须拒绝。
     */
    @Test
    void shouldRejectDuplicateOrInvalidLengthBinaryKeys() {
        String repeatedKey = base64FilledWith((byte) 3);
        Assertions.assertThrows(IllegalStateException.class,
                () -> ProductionSecurityPropertiesValidator.validateConfiguration(
                        "jwt-signing-key-that-is-longer-than-32-characters", repeatedKey, repeatedKey));
        Assertions.assertThrows(IllegalStateException.class,
                () -> ProductionSecurityPropertiesValidator.validateConfiguration(
                        "jwt-signing-key-that-is-longer-than-32-characters",
                        Base64.getEncoder().encodeToString(new byte[31]), base64FilledWith((byte) 2)));
    }

    /**
     * 构造指定字节重复 32 次的 Base64 密钥。
     *
     * @param value 重复字节
     * @return Base64 编码的 32 字节密钥
     */
    private static String base64FilledWith(byte value) {
        byte[] bytes = new byte[32];
        IntStream.range(0, bytes.length).forEach(index -> bytes[index] = value);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
