package com.example.service.notification.impl;

import com.alibaba.fastjson2.JSONArray;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;

/**
 * 钉钉机器人通知通道。基于钉钉自定义机器人 webhook 协议发送 markdown 卡片。
 * <p>
 * 配置字段：
 * <ul>
 *   <li>{@code webhook_url_enc}（加密）：完整 webhook URL（含 access_token 查询参数）。</li>
 *   <li>{@code secret_enc}（加密，可选）：启用加签时的 secret，附加 timestamp + sign 查询参数。</li>
 *   <li>{@code at_mobiles}（可选）：{@code List<String>}，@ 指定手机号。</li>
 *   <li>{@code title}（可选）：卡片标题，默认 "告警"。</li>
 * </ul>
 * 协议参考：https://open.dingtalk.com/document/robots/custom-robot-access
 */
@Slf4j
@Component
public class DingtalkNotificationChannel implements NotificationChannelSender {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    @Resource
    private RestTemplate restTemplate;

    @Override
    public String type() {
        return "dingtalk";
    }

    /**
     * 渲染钉钉 markdown 消息并发送：若配置 secret 则拼接 timestamp + sign。
     *
     * @param event  告警事件
     * @param config 通道配置（敏感字段已解密）
     * @throws Exception 调用失败抛出
     */
    @Override
    public void send(AlertEvent event, Map<String, Object> config) throws Exception {
        Object urlObj = config.get("webhook_url_enc");
        if (urlObj == null || urlObj.toString().isEmpty()) {
            throw new IllegalArgumentException("钉钉通知缺少 webhook_url_enc 配置");
        }
        String url = urlObj.toString();

        Object secretObj = config.get("secret_enc");
        if (secretObj != null && !secretObj.toString().isEmpty()) {
            url = appendSign(url, secretObj.toString());
        }

        Map<String, Object> context = NotificationFormat.buildContext(event);
        String title = stringConfig(config, "title", "告警");
        String text = renderMarkdown(context);
        JSONObject payload = new JSONObject();
        payload.put("msgtype", "markdown");
        JSONObject markdown = new JSONObject();
        markdown.put("title", title);
        markdown.put("text", text);
        payload.put("markdown", markdown);

        JSONObject at = buildAt(config.get("at_mobiles"));
        if (at != null) {
            payload.put("at", at);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(payload.toString(), headers);
        ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
        if (response.getStatusCode().isError()) {
            throw new IllegalStateException("钉钉机器人返回错误状态: " + response.getStatusCode());
        }
        log.info("钉钉通知发送成功，clientId={}, status={}", event.getClientId(), response.getStatusCode().value());
    }

    /**
     * 渲染 markdown 文本：保留 firedAt / level 等字段。
     *
     * @param context 渲染上下文
     * @return markdown 字符串
     */
    private static String renderMarkdown(Map<String, Object> context) {
        String template = """
                ### [{levelLabel}] {clientName}
                - 指标: {metricLabel}
                - 当前值: **{currentValue}**
                - 阈值: {threshold}
                - 时间: {firedAt}
                - {message}""";
        return NotificationFormat.render(template, context);
    }

    /**
     * 计算钉钉加签：timestamp + "\n" + secret 经 HmacSHA256 后再 URL 编码。
     * 将 timestamp 与 sign 作为查询参数追加到 URL。
     * <p>
     * 包外可见以便单元测试独立验证签名算法。
     *
     * @param url    原始 webhook URL
     * @param secret 加签 secret
     * @return 已附加签名的 URL
     * @throws Exception 加密异常
     */
    public static String appendSign(String url, String secret) throws Exception {
        long timestamp = System.currentTimeMillis();
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
        byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        String sign = URLEncoder.encode(Base64.getEncoder().encodeToString(signData), StandardCharsets.UTF_8);
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "timestamp=" + timestamp + "&sign=" + sign;
    }

    /**
     * 构造钉钉 at 字段：若配置了手机号列表，则 atMobiles + isAtAll=false。
     *
     * @param raw 原始 at_mobiles 配置
     * @return at JSON 对象或 null
     */
    private static JSONObject buildAt(Object raw) {
        if (raw == null) {
            return null;
        }
        JSONArray mobiles = new JSONArray();
        if (raw instanceof Collection<?> coll) {
            for (Object item : coll) {
                if (item == null) {
                    continue;
                }
                String s = item.toString().trim();
                if (!s.isEmpty()) {
                    mobiles.add(s);
                }
            }
        } else {
            String text = raw.toString();
            for (String part : text.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    mobiles.add(trimmed);
                }
            }
        }
        if (mobiles.isEmpty()) {
            return null;
        }
        JSONObject at = new JSONObject();
        at.put("atMobiles", mobiles);
        at.put("isAtAll", false);
        return at;
    }

    private static String stringConfig(Map<String, Object> config, String key, String defaultValue) {
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = value.toString();
        return text.isEmpty() ? defaultValue : text;
    }
}
