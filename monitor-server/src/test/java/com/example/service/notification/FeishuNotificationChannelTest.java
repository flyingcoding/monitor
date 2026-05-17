package com.example.service.notification;

import com.alibaba.fastjson2.JSONObject;
import com.example.entity.alert.AlertEvent;
import com.example.service.notification.impl.FeishuNotificationChannel;
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
 * FeishuNotificationChannel 单元测试。验证 text 消息构造与加签 payload 注入。
 */
class FeishuNotificationChannelTest {

    private FeishuNotificationChannel channel;
    private final AtomicReference<String> capturedUrl = new AtomicReference<>();
    private final AtomicReference<HttpEntity<?>> capturedEntity = new AtomicReference<>();
    private final AtomicReference<HttpStatus> nextStatus = new AtomicReference<>(HttpStatus.OK);

    /**
     * 装配 channel + RestTemplate 匿名子类。
     */
    @BeforeEach
    void setUp() {
        channel = new FeishuNotificationChannel();
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
     * 无 secret 时发送基础 text 消息。
     */
    @Test
    void shouldPostTextWithoutSign() throws Exception {
        AlertEvent event = baseEvent();
        Map<String, Object> config = new HashMap<>();
        config.put("webhook_url_enc", "https://open.feishu.cn/open-apis/bot/v2/hook/xxx");

        channel.send(event, config);

        Assertions.assertEquals("https://open.feishu.cn/open-apis/bot/v2/hook/xxx", capturedUrl.get());
        JSONObject payload = JSONObject.parse(capturedEntity.get().getBody().toString());
        Assertions.assertEquals("text", payload.getString("msg_type"));
        String text = payload.getJSONObject("content").getString("text");
        Assertions.assertTrue(text.contains("[警告] web-01 CPU 使用率"));
        Assertions.assertTrue(text.contains("当前 0.91"));
        Assertions.assertTrue(text.contains("阈值 0.8"));
        Assertions.assertTrue(text.contains("2026-05-17 01:02:03"));
        Assertions.assertNull(payload.get("sign"));
    }

    /**
     * 配置 secret 后 payload 应携带 timestamp 与 sign 字段。
     */
    @Test
    void shouldIncludeSignWhenSecretProvided() throws Exception {
        AlertEvent event = baseEvent();
        Map<String, Object> config = new HashMap<>();
        config.put("webhook_url_enc", "https://open.feishu.cn/open-apis/bot/v2/hook/xxx");
        config.put("secret_enc", "FEISHU_TEST_SECRET");

        channel.send(event, config);

        JSONObject payload = JSONObject.parse(capturedEntity.get().getBody().toString());
        Assertions.assertNotNull(payload.getString("timestamp"));
        Assertions.assertNotNull(payload.getString("sign"));
        Assertions.assertFalse(payload.getString("sign").isEmpty());
    }

    /**
     * sign 算法可独立验证：相同 timestamp + secret 产出相同签名（飞书 sign 与时间戳绑定）。
     */
    @Test
    void signShouldBeDeterministicForFixedInputs() throws Exception {
        String s1 = FeishuNotificationChannel.sign(1700000000L, "abc");
        String s2 = FeishuNotificationChannel.sign(1700000000L, "abc");
        Assertions.assertEquals(s1, s2);
        Assertions.assertFalse(s1.isEmpty());
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
    void typeShouldReturnFeishu() {
        Assertions.assertEquals("feishu", channel.type());
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
