package com.example.utils;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 密码复杂度策略单测（v1.2 PRD R28 / AC13）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>{@code none} 策略下任意密码通过；</li>
 *   <li>{@code basic} 策略下 长度/字母/数字 三条要求；</li>
 *   <li>错误消息为中文（项目惯例）；</li>
 *   <li>非法配置值降级为 none，不抛异常。</li>
 * </ul>
 */
class PasswordPolicyValidatorTest {

    /**
     * 构造一个跳过 @Value 注入的实例。
     */
    private PasswordPolicyValidator newValidator(String policy) {
        PasswordPolicyValidator v = new PasswordPolicyValidator();
        v.setPolicyForTest(policy);
        return v;
    }

    @Test
    void noneShouldAcceptAnyPassword() {
        PasswordPolicyValidator v = newValidator("none");
        Assertions.assertNull(v.validate(""));
        Assertions.assertNull(v.validate("a"));
        Assertions.assertNull(v.validate("abc"));
        Assertions.assertNull(v.validate("LongAndComplex123!"));
    }

    @Test
    void basicShouldRejectShortPassword() {
        PasswordPolicyValidator v = newValidator("basic");
        String err = v.validate("ab1");
        Assertions.assertNotNull(err);
        Assertions.assertTrue(err.contains("8"));
    }

    @Test
    void basicShouldRejectAllLetters() {
        PasswordPolicyValidator v = newValidator("basic");
        String err = v.validate("abcdefgh");
        Assertions.assertNotNull(err);
        Assertions.assertTrue(err.contains("字母与数字"));
    }

    @Test
    void basicShouldRejectAllDigits() {
        PasswordPolicyValidator v = newValidator("basic");
        Assertions.assertNotNull(v.validate("12345678"));
    }

    @Test
    void basicShouldAcceptLetterPlusDigit() {
        PasswordPolicyValidator v = newValidator("basic");
        Assertions.assertNull(v.validate("abcd1234"));
        Assertions.assertNull(v.validate("Abc12345"));
        Assertions.assertNull(v.validate("password1"));
    }

    @Test
    void basicShouldRejectNullOrBlank() {
        PasswordPolicyValidator v = newValidator("basic");
        Assertions.assertNotNull(v.validate(null));
        Assertions.assertNotNull(v.validate(""));
    }

    @Test
    void invalidPolicyValueShouldDegradeToNone() {
        PasswordPolicyValidator v = newValidator("strict");
        // setPolicyForTest 直接接受任意值，但 init() 路径才会校验。
        // 这里手动调 init() 模拟 @PostConstruct，并验证降级。
        org.springframework.test.util.ReflectionTestUtils.setField(v, "policy", "strict");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(v, "init");
        Assertions.assertEquals("none", v.currentPolicy());
        Assertions.assertNull(v.validate("abc"));
    }

    @Test
    void emptyPolicyDefaultsToNoneAfterInit() {
        PasswordPolicyValidator v = new PasswordPolicyValidator();
        org.springframework.test.util.ReflectionTestUtils.setField(v, "policy", "");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(v, "init");
        Assertions.assertEquals("none", v.currentPolicy());
    }
}
