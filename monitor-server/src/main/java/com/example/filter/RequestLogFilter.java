package com.example.filter;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.utils.Const;
import com.example.utils.SnowflakeIdGenerator;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 请求日志过滤器，用于记录所有用户请求信息。
 *
 * <p>v1.2 PRD R31/AC14 安全加固：参数日志中 case-insensitive 字段名匹配
 * {@link #SENSITIVE_KEY_HINTS}（password / token / secret / code / key / authorization）时，
 * 将值替换为 {@code ***}，避免在生产 JSON 日志（{@code LogstashEncoder}）里持久化用户凭据。
 * 嵌套 {@code JSONObject} / {@code JSONArray} 递归遍历；非嵌套字符串值原样保留。
 */
@Slf4j
@Component
public class RequestLogFilter extends OncePerRequestFilter {

    /**
     * 命中以下任一子串（case-insensitive）则视为敏感字段。
     * <p>选择子串匹配（如 {@code newPassword}、{@code apiToken} 均命中）以覆盖常见命名变体。
     */
    private static final Set<String> SENSITIVE_KEY_HINTS = Set.of(
            "password", "token", "secret", "code", "key", "authorization"
    );

    /**
     * 脱敏占位值，与项目其它地方（{@code NotificationChannelVO.getMaskedConfig}）保持一致。
     */
    private static final String MASKED_VALUE = "***";

    @Resource
    SnowflakeIdGenerator generator;

    private final Set<String> ignores = Set.of("/swagger-ui", "/v3/api-docs","/monitor/runtime","/monitor/heartbeat","/monitor/offline","/api/monitor/runtime-now","/api/monitor/runtime_now","/api/monitor/list","/api/monitor/runtime_history","/doc.html","/webjars","/favicon.ico");

    @Override
    protected void doFilterInternal(HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull FilterChain filterChain) throws ServletException, IOException {
        if(this.isIgnoreUrl(request.getServletPath())) {
            filterChain.doFilter(request, response);
        } else {
            long startTime = System.currentTimeMillis();
            this.logRequestStart(request);
            ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
            filterChain.doFilter(request, wrapper);
            this.logRequestEnd(wrapper, startTime);
            wrapper.copyBodyToResponse();
        }
    }

    /**
     * 判定当前请求url是否不需要日志打印
     * @param url 路径
     * @return 是否忽略
     */
    private boolean isIgnoreUrl(String url){
        for (String ignore : ignores) {
            if(url.startsWith(ignore)) return true;
        }
        return false;
    }

    /**
     * 请求结束时的日志打印，包含处理耗时以及响应结果。
     *
     * <p>v1.2 P1-1 修复：响应体也走同一份 {@link #SENSITIVE_KEY_HINTS} 脱敏列表，
     * 防止一次性下发的 {@code api_token} / {@code client_secret} 等明文进入持久化日志，
     * 破坏 "明文 token 只在创建时显示一次" 的 AC6 契约。
     *
     * <p>判定与处理流程：
     * <ol>
     *   <li>非 200 状态码：原样打印 {@code "<status> 错误"}（不读 body）；</li>
     *   <li>响应 {@code Content-Type} 为 {@code application/json}：尝试解析为
     *       {@link JSONObject} / {@link JSONArray} 走 {@link #redact} 递归脱敏；解析失败回退原文；</li>
     *   <li>非 JSON（HTML 错误页 / 空 body / text）：原样输出。</li>
     * </ol>
     *
     * @param wrapper   用于读取响应结果的包装类
     * @param startTime 起始时间
     */
    public void logRequestEnd(ContentCachingResponseWrapper wrapper, long startTime){
        long time = System.currentTimeMillis() - startTime;
        int status = wrapper.getStatus();
        String content;
        if (status != 200) {
            content = status + " 错误";
        } else {
            String raw = new String(wrapper.getContentAsByteArray());
            content = redactJsonString(raw, wrapper.getContentType());
        }
        log.info("请求处理耗时: {}ms | 响应结果: {}", time, content);
    }

    /**
     * 请求开始时的日志打印，包含请求全部信息，以及对应用户角色
     * @param request 请求
     */
    public void logRequestStart(HttpServletRequest request){
        long reqId = generator.nextId();
        MDC.put("reqId", String.valueOf(reqId));
        JSONObject object = new JSONObject();
        request.getParameterMap().forEach((k, v) -> {
            Object value = v.length > 0 ? v[0] : null;
            object.put(k, isSensitiveKey(k) ? MASKED_VALUE : value);
        });
        Object id = request.getAttribute(Const.ATTR_USER_ID);
        if(id != null) {
            User user = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            log.info("请求URL: \"{}\" ({}) | 远程IP地址: {} │ 身份: {} (UID: {}) | 角色: {} | 请求参数列表: {}",
                    request.getServletPath(), request.getMethod(), request.getRemoteAddr(),
                    user.getUsername(), id, user.getAuthorities(), object);
        } else {
            log.info("请求URL: \"{}\" ({}) | 远程IP地址: {} │ 身份: 未验证 | 请求参数列表: {}",
                    request.getServletPath(), request.getMethod(), request.getRemoteAddr(), object);
        }
    }

    /**
     * Case-insensitive 子串匹配：字段名包含 password / token / secret / code / key / authorization
     * 任一关键词即视为敏感。
     *
     * @param key 字段名（可能为 null）
     * @return 是否敏感
     */
    static boolean isSensitiveKey(String key) {
        if (key == null || key.isBlank()) return false;
        String lower = key.toLowerCase(Locale.ROOT);
        for (String hint : SENSITIVE_KEY_HINTS) {
            if (lower.contains(hint)) return true;
        }
        return false;
    }

    /**
     * 递归脱敏 {@link JSONObject}：嵌套对象 / 数组同步处理；命中敏感字段时整体替换值为
     * {@link #MASKED_VALUE}（即便它本身是嵌套对象，也不再递归）。
     *
     * <p>返回新 {@link JSONObject} 实例，避免污染原始参数。供 body 日志或上层调用使用。
     *
     * @param input 原始 JSON 对象（可能为 null）
     * @return 脱敏后的副本；input 为 null 时返回 null
     */
    static JSONObject redact(JSONObject input) {
        if (input == null) return null;
        JSONObject out = new JSONObject(input.size());
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (isSensitiveKey(key)) {
                out.put(key, MASKED_VALUE);
                continue;
            }
            out.put(key, redactValue(value));
        }
        return out;
    }

    /**
     * 对任意 JSON 值递归脱敏；非容器类型原样返回。
     */
    private static Object redactValue(Object value) {
        if (value instanceof JSONObject json) {
            return redact(json);
        }
        if (value instanceof JSONArray arr) {
            JSONArray out = new JSONArray(arr.size());
            for (Object item : arr) {
                out.add(redactValue(item));
            }
            return out;
        }
        return value;
    }

    /**
     * 对 application/json 响应体执行脱敏。只在内容类型明确为 JSON 时尝试解析；
     * 解析失败或非 JSON 内容（HTML 错误页 / 纯文本）则原样返回。
     *
     * <p>与请求侧 {@link #redact(JSONObject)} 共享同一份 {@link #SENSITIVE_KEY_HINTS}
     * 关键字列表，符合 logging-guidelines.md §Scenario: Request-log field redaction
     * 的契约：响应路径不允许另立白名单。
     *
     * @param raw         原始响应字符串
     * @param contentType 响应 Content-Type（可能为 null）
     * @return 脱敏后字符串；非 JSON / 解析失败时返回原文
     */
    static String redactJsonString(String raw, String contentType) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("json")) {
            return raw;
        }
        String trimmed = raw.stripLeading();
        if (trimmed.isEmpty()) {
            return raw;
        }
        char head = trimmed.charAt(0);
        try {
            if (head == '{') {
                JSONObject parsed = JSONObject.parse(raw);
                return parsed == null ? raw : redact(parsed).toString();
            }
            if (head == '[') {
                JSONArray parsed = JSONArray.parse(raw);
                return parsed == null ? raw : ((JSONArray) redactValue(parsed)).toString();
            }
        } catch (Exception ignored) {
            // 解析失败回退原文：日志可读性优先，但敏感字段在源头（请求侧）已经脱敏
        }
        return raw;
    }
}
