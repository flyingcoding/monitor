package com.example.service.notification.impl;

import com.alibaba.fastjson2.JSONObject;
import com.example.entity.alert.AlertEvent;
import com.example.service.notification.NotificationChannelSender;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * 飞书自定义机器人通知通道。基于飞书 v3 自定义机器人 webhook 协议发送 text 消息。
 * <p>
 * 配置字段：
 * <ul>
 *   <li>{@code webhook_url_enc}（加密）：完整 webhook URL。</li>
 *   <li>{@code secret_enc}（加密，可选）：启用加签时的 secret，会向 payload 注入 timestamp 与 sign。</li>
 * </ul>
 * 协议参考：https://open.feishu.cn/document/client-docs/bot-v3/add-custom-bot
 */
@Slf4j
@Component
public class FeishuNotificationChannel implements NotificationChannelSender {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    @Resource
    private RestTemplate restTemplate;

    @Override
    public String type() {
        return "feishu";
    }

    /**
     * 渲染飞书 text 消息并发送。配置 secret 时计算加签并注入 payload。
     *
     * @param event  告警事件
     * @param config 通道配置（敏感字段已解密）
     * @throws Exception 调用失败抛出
     */
    @Override
    public void send(AlertEvent event, Map<String, Object> config) throws Exception {
        Object urlObj = config.get("webhook_url_enc");
        if (urlObj == null || urlObj.toString().isEmpty()) {
            throw new IllegalArgumentException("飞书通知缺少 webhook_url_enc 配置");
        }
        String url = urlObj.toString();

        Map<String, Object> context = NotificationFormat.buildContext(event);
        String text = NotificationFormat.render(
                "[{levelLabel}] {clientName} {metricLabel} 告警: 当前 {currentValue}, 阈值 {threshold} ({firedAt})",
                context
        );

        JSONObject payload = new JSONObject();
        payload.put("msg_type", "text");
        JSONObject content = new JSONObject();
        content.put("text", text);
        payload.put("content", content);

        Object secretObj = config.get("secret_enc");
        if (secretObj != null && !secretObj.toString().isEmpty()) {
            long timestamp = System.currentTimeMillis() / 1000;
            String sign = sign(timestamp, secretObj.toString());
            payload.put("timestamp", String.valueOf(timestamp));
            payload.put("sign", sign);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(payload.toString(), headers);
        ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
        if (response.getStatusCode().isError()) {
            throw new IllegalStateException("飞书机器人返回错误状态: " + response.getStatusCode());
        }
        log.info("飞书通知发送成功，clientId={}, status={}", event.getClientId(), response.getStatusCode().value());
    }

    /**
     * 计算飞书加签：sign = HmacSHA256(timestamp + "\n" + secret, "") 后 Base64 编码。
     * 注意：飞书加签算法是把"timestamp\nsecret"作为密钥而不是消息，消息为空。
     * <p>
     * 包外可见以便单元测试独立验证签名算法。
     *
     * @param timestamp 秒级时间戳
     * @param secret    机器人 secret
     * @return Base64 编码后的签名字符串
     * @throws Exception 加密异常
     */
    public static String sign(long timestamp, String secret) throws Exception {
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(stringToSign.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
        byte[] signData = mac.doFinal(new byte[]{});
        return Base64.getEncoder().encodeToString(signData);
    }
}
