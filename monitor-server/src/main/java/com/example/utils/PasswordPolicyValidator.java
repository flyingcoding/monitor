package com.example.utils;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 密码复杂度策略校验（v1.2 PRD R28 / AC13）。
 *
 * <p>双档 yaml 可配：
 * <ul>
 *   <li>{@code monitor.password.policy=none}（默认 dev）：不强制；</li>
 *   <li>{@code monitor.password.policy=basic}（默认 prod）：长度 ≥ 8 且含字母与数字。</li>
 * </ul>
 *
 * <p>生效场景：注册（{@code createSubAccount}）、重置（{@code resetEmailAccountPassword}）、
 * 修改密码（{@code changePassword}）。老用户密码不强制升级。
 */
@Slf4j
@Component
public class PasswordPolicyValidator {

    private static final String POLICY_NONE = "none";
    private static final String POLICY_BASIC = "basic";

    /**
     * basic 档最小长度。
     */
    private static final int BASIC_MIN_LENGTH = 8;

    @Value("${monitor.password.policy:none}")
    private String policy;

    @PostConstruct
    void init() {
        if (policy == null || policy.isBlank()) {
            policy = POLICY_NONE;
        }
        policy = policy.trim().toLowerCase();
        if (!POLICY_NONE.equals(policy) && !POLICY_BASIC.equals(policy)) {
            log.warn("monitor.password.policy 配置值非法：{}，降级为 none", policy);
            policy = POLICY_NONE;
        }
    }

    /**
     * 校验密码是否满足当前策略。
     *
     * @param password 明文密码（可能为 null）
     * @return null 表示通过；非 null 时为面向用户的中文错误消息
     */
    public String validate(String password) {
        if (POLICY_NONE.equals(policy)) {
            return null;
        }
        if (password == null || password.isEmpty()) {
            return "密码不能为空";
        }
        if (POLICY_BASIC.equals(policy)) {
            if (password.length() < BASIC_MIN_LENGTH) {
                return "密码至少 " + BASIC_MIN_LENGTH + " 位，并包含字母与数字";
            }
            boolean hasLetter = false;
            boolean hasDigit = false;
            for (int i = 0; i < password.length(); i++) {
                char c = password.charAt(i);
                if (Character.isLetter(c)) hasLetter = true;
                else if (Character.isDigit(c)) hasDigit = true;
                if (hasLetter && hasDigit) break;
            }
            if (!hasLetter || !hasDigit) {
                return "密码必须同时包含字母与数字";
            }
        }
        return null;
    }

    /**
     * 暴露当前策略（仅供测试与日志诊断）。
     */
    public String currentPolicy() {
        return policy;
    }

    /**
     * 测试钩子：直接覆盖策略，跳过 @Value 注入。
     */
    void setPolicyForTest(String policy) {
        this.policy = policy == null ? POLICY_NONE : policy.toLowerCase();
    }
}
