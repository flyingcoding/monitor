package com.example.service.notification.impl;

import com.example.entity.alert.AlertEvent;
import com.example.service.notification.NotificationChannelSender;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通用 Webhook 通知通道。向用户配置的 URL 发送 HTTP POST，请求体为 JSON。
 * <p>
 * 配置字段：
 * <ul>
 *   <li>{@code url}（明文）或 {@code url_enc}（CryptoUtils 加密，含 token 等敏感信息），二选一。</li>
 *   <li>{@code body_template}（可选）：JSON 字符串模板；占位符 {@code {key}} 会被 JSON 安全的字面量替换
 *       （字符串值会自动加引号并对特殊字符 {@code " \ \n} 等做转义）。未配置时使用默认 schema。</li>
 *   <li>{@code headers_enc}（推荐）：整个 headers Map 序列化后整体加密。
 *       Listener 解密后值为 JSON 字符串（如 {@code "{\"X-Token\":\"sk-xxx\"}"}），本通道在使用前反序列化为 Map。</li>
 *   <li>{@code headers}（兼容）：{@code Map<String,String>} 明文，仅用于向后兼容非敏感场景。
 *       同时配置时优先使用 {@code headers_enc}。</li>
 *   <li>{@code method}（可选）：默认 POST，可配置为 PUT。</li>
 * </ul>
 * <p>
 * 默认行为采用 {@link ObjectMapper#writeValueAsString(Object)} 序列化 {@code Map}，
 * 避免历史实现中 {@code replace("{message}", value)} 在用户输入含 {@code " \ \n} 时
 * 生成非法 JSON 的注入隐患。
 */
@Slf4j
@Component
public class WebhookNotificationChannel implements NotificationChannelSender {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> HEADERS_TYPE =
            new TypeReference<>() {};

    @Resource
    private RestTemplate restTemplate;

    @Override
    public String type() {
        return "webhook";
    }

    /**
     * 调用用户配置的 Webhook：渲染请求体并 POST。失败时抛出异常以触发 RabbitMQ 重试或落入死信。
     *
     * @param event  告警事件
     * @param config 通道配置（敏感字段已解密，{@code url_enc} 已映射为 {@code url}）
     * @throws Exception 调用失败抛出
     */
    @Override
    public void send(AlertEvent event, Map<String, Object> config) throws Exception {
        String url = resolveUrl(config);
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("Webhook 通知缺少 url 配置");
        }

        String body = buildBody(event, config);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // 优先使用 headers_enc（已被 Listener 解密为 JSON 字符串），回退到明文 headers 兼容老配置
        Object headersEnc = config.get("headers_enc");
        if (headersEnc != null) {
            applyEncryptedHeaders(headers, headersEnc);
        } else {
            applyExtraHeaders(headers, config.get("headers"));
        }

        String methodName = stringConfig(config, "method", "POST").toUpperCase();
        HttpMethod httpMethod = "PUT".equals(methodName) ? HttpMethod.PUT : HttpMethod.POST;

        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, httpMethod, entity, String.class);
        if (response.getStatusCode().isError()) {
            throw new IllegalStateException("Webhook 调用失败，状态码: " + response.getStatusCode());
        }
        log.info("Webhook 通知发送成功，clientId={}, status={}", event.getClientId(), response.getStatusCode().value());
    }

    /**
     * 构造请求体：未配置 body_template 时使用 Jackson 序列化默认 schema；
     * 配置了 body_template 时按 JSON 安全方式替换占位符（值经 Jackson 转义后再嵌入）。
     *
     * @param event  告警事件
     * @param config 通道配置
     * @return 请求体 JSON 字符串
     * @throws Exception 序列化或渲染失败抛出
     */
    private String buildBody(AlertEvent event, Map<String, Object> config) throws Exception {
        String template = stringConfig(config, "body_template", null);
        if (template == null) {
            return OBJECT_MAPPER.writeValueAsString(defaultPayload(event));
        }
        return renderJsonSafe(template, event);
    }

    /**
     * 构造默认 schema 的 payload。字段顺序确定（LinkedHashMap）以便测试可读，
     * 但接收方应按 key 取值而非依赖顺序。
     *
     * @param event 告警事件
     * @return payload Map
     */
    private static Map<String, Object> defaultPayload(AlertEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "alert");
        payload.put("level", event.getLevel());
        payload.put("client", event.getClientName());
        payload.put("metric", event.getMetric());
        payload.put("value", event.getCurrentValue());
        payload.put("threshold", event.getThreshold());
        payload.put("message", event.getMessage());
        payload.put("firedAt", event.getFiredAt() == null
                ? null
                : NotificationFormat.TIMESTAMP_FORMAT.format(event.getFiredAt()));
        return payload;
    }

    /**
     * 对自定义模板做 JSON 安全的占位符替换。
     * <p>
     * 替换逻辑：将 {@code {key}} 整体替换为 {@code ObjectMapper.writeValueAsString(value)}，
     * 字符串值会被 Jackson 加引号 + 对 {@code " \ \n} 等转义，数值/布尔/null 保留为字面量。
     * 因此模板里写 {@code {message}}（不要再外加引号），渲染后才是合法 JSON。
     *
     * @param template 含 {@code {key}} 占位符的模板
     * @param event    告警事件
     * @return 渲染后的 JSON 字符串
     * @throws Exception Jackson 序列化失败抛出
     */
    private static String renderJsonSafe(String template, AlertEvent event) throws Exception {
        Map<String, Object> context = NotificationFormat.buildContext(event);
        String result = template;
        for (Map.Entry<String, Object> entry : context.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            if (!result.contains(placeholder)) {
                continue;
            }
            String literal = OBJECT_MAPPER.writeValueAsString(entry.getValue());
            result = result.replace(placeholder, literal);
        }
        return result;
    }

    /**
     * 解析配置中的目标 URL：优先取已解密的 url_enc，其次取 url 明文。
     *
     * @param config 通道配置
     * @return URL 字符串或 null
     */
    private static String resolveUrl(Map<String, Object> config) {
        Object encrypted = config.get("url_enc");
        if (encrypted != null && !encrypted.toString().isEmpty()) {
            return encrypted.toString();
        }
        Object plain = config.get("url");
        return plain == null ? null : plain.toString();
    }

    /**
     * 将用户配置的额外请求头附加到 HttpHeaders 上。
     *
     * @param headers 请求头容器
     * @param raw     原始配置值
     */
    private static void applyExtraHeaders(HttpHeaders headers, Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            headers.add(entry.getKey().toString(), entry.getValue().toString());
        }
    }

    /**
     * 将经过 {@code _enc} 解密后的 headers 值附加到 HttpHeaders。
     * <p>
     * 入参可能形态：
     * <ul>
     *   <li>JSON 字符串（{@link com.example.service.impl.NotificationChannelServiceImpl#encryptSensitive}
     *       会把 Map 先 JSON 序列化为字符串再加密，解密后还原为字符串）</li>
     *   <li>Map（兼容直接传 Map 的测试场景或老路径）</li>
     * </ul>
     * JSON 字符串解析失败时记 WARN 后跳过，避免影响主请求发送。
     *
     * @param headers 请求头容器
     * @param raw     解密后的 headers 值
     */
    private static void applyEncryptedHeaders(HttpHeaders headers, Object raw) {
        if (raw instanceof Map<?, ?>) {
            applyExtraHeaders(headers, raw);
            return;
        }
        if (raw instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return;
            }
            try {
                Map<String, String> parsed = OBJECT_MAPPER.readValue(trimmed, HEADERS_TYPE);
                applyExtraHeaders(headers, parsed);
            } catch (Exception e) {
                log.warn("Webhook headers_enc 解析失败，跳过附加 headers，reason={}", e.getMessage());
            }
        }
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
