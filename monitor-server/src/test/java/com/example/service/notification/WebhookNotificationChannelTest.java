package com.example.service.notification;

import com.example.entity.alert.AlertEvent;
import com.example.service.notification.impl.WebhookNotificationChannel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebhookNotificationChannel 单元测试。验证 URL 解析、模板渲染、自定义 headers。
 * <p>
 * RestTemplate 不是接口，无法用 JDK 动态代理；采用匿名子类覆盖 exchange 方法捕获参数。
 * <p>
 * 体校验统一通过 Jackson 反序列化为 {@link JsonNode}，避免对模板字段顺序或字面字符串的脆弱依赖，
 * 同时验证生成的请求体是合法 JSON（防止历史 string-replace 实现的注入隐患复活）。
 */
class WebhookNotificationChannelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WebhookNotificationChannel channel;
    private final AtomicReference<String> capturedUrl = new AtomicReference<>();
    private final AtomicReference<HttpMethod> capturedMethod = new AtomicReference<>();
    private final AtomicReference<HttpEntity<?>> capturedEntity = new AtomicReference<>();
    private final AtomicReference<HttpStatus> nextStatus = new AtomicReference<>(HttpStatus.OK);

    /**
     * 装配 channel + 匿名子类的 RestTemplate 桩。
     */
    @BeforeEach
    void setUp() {
        channel = new WebhookNotificationChannel();
        capturedUrl.set(null);
        capturedMethod.set(null);
        capturedEntity.set(null);
        nextStatus.set(HttpStatus.OK);
        RestTemplate fake = new RestTemplate() {
            @Override
            public <T> ResponseEntity<T> exchange(String url, HttpMethod method, HttpEntity<?> requestEntity,
                                                  Class<T> responseType, Object... uriVariables) {
                capturedUrl.set(url);
                capturedMethod.set(method);
                capturedEntity.set(requestEntity);
                @SuppressWarnings("unchecked")
                ResponseEntity<T> resp = (ResponseEntity<T>) new ResponseEntity<>("ok", nextStatus.get());
                return resp;
            }
        };
        ReflectionTestUtils.setField(channel, "restTemplate", fake);
    }

    /**
     * 默认 schema：通过 Jackson 序列化 Map，请求体必须是合法 JSON 且字段值与 AlertEvent 一致。
     */
    @Test
    void shouldPostDefaultBodyToPlainUrl() throws Exception {
        AlertEvent event = baseEvent();
        Map<String, Object> config = new HashMap<>();
        config.put("url", "https://hook.example.com/alert");

        channel.send(event, config);

        Assertions.assertEquals("https://hook.example.com/alert", capturedUrl.get());
        Assertions.assertEquals(HttpMethod.POST, capturedMethod.get());
        Assertions.assertNotNull(capturedEntity.get());
        Object body = capturedEntity.get().getBody();
        Assertions.assertNotNull(body);
        JsonNode payload = MAPPER.readTree(body.toString());
        Assertions.assertEquals("alert", payload.get("event").asText());
        Assertions.assertEquals("warning", payload.get("level").asText());
        Assertions.assertEquals("web-01", payload.get("client").asText());
        Assertions.assertEquals("cpu", payload.get("metric").asText());
        Assertions.assertEquals(0.91, payload.get("value").asDouble(), 1e-9);
        Assertions.assertEquals(0.8, payload.get("threshold").asDouble(), 1e-9);
        Assertions.assertEquals("2026-05-17 01:02:03", payload.get("firedAt").asText());
        HttpHeaders headers = capturedEntity.get().getHeaders();
        Assertions.assertEquals("application/json", headers.getContentType().toString());
    }

    /**
     * url_enc 优先于 url 字段：模拟 Listener 已解密 url_enc 为明文 URL。
     */
    @Test
    void shouldPreferUrlEncOverUrl() throws Exception {
        AlertEvent event = baseEvent();
        Map<String, Object> config = new HashMap<>();
        config.put("url", "https://fallback.example.com");
        config.put("url_enc", "https://hook.example.com/secret?token=abc");

        channel.send(event, config);

        Assertions.assertEquals("https://hook.example.com/secret?token=abc", capturedUrl.get());
    }

    /**
     * 自定义 body_template 与 headers 应生效：占位符 {currentValue} 是数值字面量、
     * {clientName} 是 JSON 字符串字面量（含引号），用户在模板中不应再手动加引号。
     */
    @Test
    void shouldUseCustomTemplateAndHeaders() throws Exception {
        AlertEvent event = baseEvent();
        Map<String, Object> config = new HashMap<>();
        config.put("url", "https://hook.example.com/alert");
        // 注意模板里 {clientName} 不再手动加引号 —— Jackson 会序列化为带引号的 JSON 字面量
        config.put("body_template", "{\"name\":{clientName},\"v\":{currentValue}}");
        Map<String, String> extra = new HashMap<>();
        extra.put("X-Token", "secret");
        extra.put("X-Source", "monitor");
        config.put("headers", extra);

        channel.send(event, config);

        String body = capturedEntity.get().getBody().toString();
        JsonNode payload = MAPPER.readTree(body);
        Assertions.assertEquals("web-01", payload.get("name").asText());
        Assertions.assertEquals(0.91, payload.get("v").asDouble(), 1e-9);
        HttpHeaders headers = capturedEntity.get().getHeaders();
        Assertions.assertEquals("secret", headers.getFirst("X-Token"));
        Assertions.assertEquals("monitor", headers.getFirst("X-Source"));
    }

    /**
     * 非 2xx 响应应抛异常以触发 RabbitMQ 重试 / DLX。
     */
    @Test
    void shouldThrowOnNon2xxResponse() {
        nextStatus.set(HttpStatus.INTERNAL_SERVER_ERROR);
        AlertEvent event = baseEvent();
        Map<String, Object> config = new HashMap<>();
        config.put("url", "https://hook.example.com/alert");
        Assertions.assertThrows(IllegalStateException.class, () -> channel.send(event, config));
    }

    /**
     * 缺少 url 配置应抛 IllegalArgumentException。
     */
    @Test
    void shouldFailWhenUrlMissing() {
        AlertEvent event = baseEvent();
        Map<String, Object> config = new HashMap<>();
        Assertions.assertThrows(IllegalArgumentException.class, () -> channel.send(event, config));
    }

    /**
     * PUT method 支持。
     */
    @Test
    void shouldSupportPutMethod() throws Exception {
        AlertEvent event = baseEvent();
        Map<String, Object> config = new HashMap<>();
        config.put("url", "https://hook.example.com/alert");
        config.put("method", "PUT");

        channel.send(event, config);

        Assertions.assertEquals(HttpMethod.PUT, capturedMethod.get());
    }

    /**
     * 通道类型标识。
     */
    @Test
    void typeShouldReturnWebhook() {
        Assertions.assertEquals("webhook", channel.type());
    }

    /**
     * 默认 body 在字段值含 JSON 特殊字符（{@code " \ \n}）时仍应生成合法 JSON，
     * 反序列化后字段值与原值完全一致 —— 防止字符串拼接式的注入隐患复活。
     */
    @Test
    void should_render_default_body_with_special_chars() throws Exception {
        AlertEvent event = AlertEvent.builder()
                .ruleId(1L)
                .historyId(99L)
                .clientId(7)
                .clientName("c\\1\"")
                .metric("cpu")
                .operator("gt")
                .threshold(0.8)
                .currentValue(0.91)
                .level("warning")
                .message("He said \"hi\"\nand left")
                .firedAt(LocalDateTime.of(2026, 5, 17, 1, 2, 3))
                .channelIds(List.of(1L))
                .build();
        Map<String, Object> config = new HashMap<>();
        config.put("url", "https://hook.example.com/alert");

        channel.send(event, config);

        String body = capturedEntity.get().getBody().toString();
        // 必须是合法 JSON（任何转义错误都会让 readTree 抛 JsonParseException）
        JsonNode payload = MAPPER.readTree(body);
        Assertions.assertEquals("c\\1\"", payload.get("client").asText());
        Assertions.assertEquals("He said \"hi\"\nand left", payload.get("message").asText());
        Assertions.assertEquals("warning", payload.get("level").asText());
        Assertions.assertEquals("cpu", payload.get("metric").asText());
    }

    private static AlertEvent baseEvent() {
        return AlertEvent.builder()
                .ruleId(1L)
                .historyId(99L)
                .clientId(7)
                .clientName("web-01")
                .metric("cpu")
                .operator("gt")
                .threshold(0.8)
                .currentValue(0.91)
                .level("warning")
                .message("CPU 持续过高")
                .firedAt(LocalDateTime.of(2026, 5, 17, 1, 2, 3))
                .channelIds(List.of(1L))
                .build();
    }
}
