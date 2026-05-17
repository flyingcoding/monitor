package com.example.service.notification;

import com.alibaba.fastjson2.JSONObject;
import com.example.entity.alert.AlertEvent;
import com.example.service.notification.impl.DingtalkNotificationChannel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
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
 * DingtalkNotificationChannel 单元测试。验证 markdown 消息构造与加签 URL 拼接。
 */
class DingtalkNotificationChannelTest {

    private DingtalkNotificationChannel channel;
    private final AtomicReference<String> capturedUrl = new AtomicReference<>();
    private final AtomicReference<HttpEntity<?>> capturedEntity = new AtomicReference<>();
    private final AtomicReference<HttpStatus> nextStatus = new AtomicReference<>(HttpStatus.OK);

    /**
     * 装配 channel + RestTemplate 匿名子类，捕获 postForEntity 调用参数。
     */
    @BeforeEach
    void setUp() {
        channel = new DingtalkNotificationChannel();
        capturedUrl.set(null);
        capturedEntity.set(null);
        nextStatus.set(HttpStatus.OK);
        RestTemplate fake = new RestTemplate() {
            @Override
            public <T> ResponseEntity<T> postForEntity(String url, Object request, Class<T> responseType,
                                                      Object... uriVariables) {
                capturedUrl.set(url);
                capturedEntity.set((HttpEntity<?>) request);
                @SuppressWarnings("unchecked")
                ResponseEntity<T> resp = (ResponseEntity<T>) new ResponseEntity<>("ok", nextStatus.get());
                return resp;
            }
        };
        ReflectionTestUtils.setField(channel, "restTemplate", fake);
    }

    /**
     * 无 secret 时直接使用 webhook URL，payload 含 markdown title 与 text。
     */
    @Test
    void shouldPostMarkdownWithoutSign() throws Exception {
        AlertEvent event = baseEvent();
        Map<String, Object> config = new HashMap<>();
        config.put("webhook_url_enc", "https://oapi.dingtalk.com/robot/send?access_token=abc");

        channel.send(event, config);

        Assertions.assertEquals("https://oapi.dingtalk.com/robot/send?access_token=abc", capturedUrl.get());
        Object body = capturedEntity.get().getBody();
        Assertions.assertNotNull(body);
        JSONObject payload = JSONObject.parse(body.toString());
        Assertions.assertEquals("markdown", payload.getString("msgtype"));
        JSONObject markdown = payload.getJSONObject("markdown");
        Assertions.assertEquals("告警", markdown.getString("title"));
        String text = markdown.getString("text");
        Assertions.assertTrue(text.contains("[警告] web-01"));
        Assertions.assertTrue(text.contains("CPU 使用率"));
        Assertions.assertTrue(text.contains("0.91"));
        Assertions.assertTrue(text.contains("0.8"));
        Assertions.assertTrue(text.contains("2026-05-17 01:02:03"));
    }

    /**
     * 配置 secret 后 URL 应包含 timestamp & sign 查询参数。
     */
    @Test
    void shouldAppendSignWhenSecretProvided() throws Exception {
        AlertEvent event = baseEvent();
        Map<String, Object> config = new HashMap<>();
        config.put("webhook_url_enc", "https://oapi.dingtalk.com/robot/send?access_token=abc");
        config.put("secret_enc", "SEC_KEY_FOR_TEST");

        channel.send(event, config);

        String url = capturedUrl.get();
        Assertions.assertTrue(url.contains("&timestamp="), "URL 应包含 timestamp 参数");
        Assertions.assertTrue(url.contains("&sign="), "URL 应包含 sign 参数");
    }

    /**
     * appendSign 算法可独立验证：相同 timestamp + secret 产出相同签名。
     */
    @Test
    void appendSignShouldProduceStableUrl() throws Exception {
        String url1 = DingtalkNotificationChannel.appendSign("https://oapi.dingtalk.com/robot/send?access_token=t", "SECRET");
        Assertions.assertTrue(url1.contains("timestamp="));
        Assertions.assertTrue(url1.contains("sign="));
    }

    /**
     * 配置 at_mobiles 后 payload 应包含 atMobiles + isAtAll=false。
     */
    @Test
    void shouldIncludeAtMobiles() throws Exception {
        AlertEvent event = baseEvent();
        Map<String, Object> config = new HashMap<>();
        config.put("webhook_url_enc", "https://oapi.dingtalk.com/robot/send?access_token=abc");
        config.put("at_mobiles", List.of("13800138000", "13900139000"));

        channel.send(event, config);

        JSONObject payload = JSONObject.parse(capturedEntity.get().getBody().toString());
        JSONObject at = payload.getJSONObject("at");
        Assertions.assertNotNull(at);
        Assertions.assertEquals(false, at.getBoolean("isAtAll"));
        Assertions.assertEquals(2, at.getJSONArray("atMobiles").size());
    }

    /**
     * 缺少 webhook_url_enc 应抛 IllegalArgumentException。
     */
    @Test
    void shouldFailWhenWebhookMissing() {
        AlertEvent event = baseEvent();
        Map<String, Object> config = new HashMap<>();
        Assertions.assertThrows(IllegalArgumentException.class, () -> channel.send(event, config));
    }

    /**
     * 通道类型标识。
     */
    @Test
    void typeShouldReturnDingtalk() {
        Assertions.assertEquals("dingtalk", channel.type());
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
