package com.example.integration.support;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;

/**
 * GreenMail 嵌入式 SMTP 服务器辅助工具。
 *
 * <p>使用方式：在需要拦截 SMTP 出口的 IT（如 AlertFlowIT）里加一个静态字段：
 * <pre>{@code
 * @RegisterExtension
 * static final GreenMailExtension SMTP = GreenMailSupport.smtpExtension();
 * }</pre>
 *
 * 监听端口固定 {@value ServerSetupTest#SMTP_PORT}（GreenMail 默认 SMTP 测试端口 3025），
 * 与 {@code application-it.yml} 的 {@code spring.mail.port} 对齐。CI 单进程跑集成测试，
 * 端口冲突风险可忽略（Failsafe forkCount 默认 1）。
 *
 * <p>默认凭证 {@code test@example.com / test}，与 CI workflow 既有 MAIL_USERNAME 一致，
 * 测试不需要额外注入用户名/密码到 application-it.yml。
 */
public final class GreenMailSupport {

    public static final String DEFAULT_USERNAME = "test@example.com";
    public static final String DEFAULT_PASSWORD = "test";

    private GreenMailSupport() {
    }

    /**
     * 构建带默认登录凭证的 GreenMailExtension。withPerMethodLifecycle(false) 让 SMTP server
     * 在整个测试类生命周期内只启一次，避免每个 @Test 重启 GreenMail（端口绑定 race condition）。
     */
    public static GreenMailExtension smtpExtension() {
        return new GreenMailExtension(ServerSetupTest.SMTP)
                .withConfiguration(GreenMailConfiguration.aConfig()
                        .withUser(DEFAULT_USERNAME, DEFAULT_PASSWORD))
                .withPerMethodLifecycle(false);
    }
}
