package com.example.integration.support;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * WireMock 嵌入式 HTTP mock 服务器辅助工具。
 *
 * <p>在需要拦截 HTTP 出口的 IT（AlertFlowIT 拦截 Webhook/钉钉/飞书；ProbeFlowIT 模拟 HTTP 探测目标）
 * 里加静态字段：
 * <pre>{@code
 * @RegisterExtension
 * static final WireMockExtension MOCK = WireMockSupport.dynamicPort();
 * }</pre>
 *
 * <p>端口由系统动态分配（{@code dynamicPort()}），测试代码用 {@code MOCK.baseUrl()} 拿 URL
 * 注入到 NotificationChannel 配置 / ProbeTask 目标。避免与 GreenMail 固定端口冲突。
 *
 * <p>{@code failOnUnmatchedRequests(false)} 让未匹配 stub 的请求不直接报错——保留断言权交给测试方法，
 * 通过 {@code MOCK.verify(...)} 显式检查出口调用。
 */
public final class WireMockSupport {

    private WireMockSupport() {
    }

    public static WireMockExtension dynamicPort() {
        return WireMockExtension.newInstance()
                .options(wireMockConfig().dynamicPort())
                .failOnUnmatchedRequests(false)
                .build();
    }
}
