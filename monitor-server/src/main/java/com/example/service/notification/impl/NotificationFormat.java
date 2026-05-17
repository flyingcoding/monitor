package com.example.service.notification.impl;

import com.example.entity.alert.AlertEvent;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 通知模板渲染与中文指标名映射工具。所有 NotificationChannel 实现共享，避免重复实现。
 */
final class NotificationFormat {

    /** 时间格式化器，用于通知模板中渲染 firedAt。 */
    static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 指标列字符串 → 中文展示名映射。 */
    private static final Map<String, String> METRIC_LABEL = Map.of(
            "cpu", "CPU 使用率",
            "memory", "内存使用率",
            "disk", "磁盘使用率",
            "network_up", "网络上行",
            "network_down", "网络下行"
    );

    /** 告警等级列字符串 → 中文展示名映射。 */
    private static final Map<String, String> LEVEL_LABEL = Map.of(
            "info", "提示",
            "warning", "警告",
            "critical", "严重"
    );

    private NotificationFormat() {
    }

    /**
     * 将指标列字符串翻译为中文展示名；未识别时原样返回。
     *
     * @param metric 指标列字符串
     * @return 中文展示名或原值
     */
    static String metricLabel(String metric) {
        if (metric == null) {
            return "";
        }
        return METRIC_LABEL.getOrDefault(metric, metric);
    }

    /**
     * 将告警等级列字符串翻译为中文展示名；未识别时原样返回。
     *
     * @param level 等级列字符串
     * @return 中文展示名或原值
     */
    static String levelLabel(String level) {
        if (level == null) {
            return "";
        }
        return LEVEL_LABEL.getOrDefault(level, level);
    }

    /**
     * 构造模板渲染上下文：将 AlertEvent 字段以及中文指标 / 等级标签放入 Map，
     * 各 channel 模板可使用 {clientName} / {metricLabel} / {levelLabel} 等占位符。
     *
     * @param event 告警事件
     * @return 渲染上下文
     */
    static Map<String, Object> buildContext(AlertEvent event) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("ruleId", event.getRuleId());
        ctx.put("historyId", event.getHistoryId());
        ctx.put("clientId", event.getClientId());
        ctx.put("clientName", nullSafe(event.getClientName()));
        ctx.put("metric", nullSafe(event.getMetric()));
        ctx.put("metricLabel", metricLabel(event.getMetric()));
        ctx.put("operator", nullSafe(event.getOperator()));
        ctx.put("threshold", event.getThreshold());
        ctx.put("currentValue", event.getCurrentValue());
        ctx.put("level", nullSafe(event.getLevel()));
        ctx.put("levelLabel", levelLabel(event.getLevel()));
        ctx.put("message", nullSafe(event.getMessage()));
        LocalDateTime firedAt = event.getFiredAt();
        ctx.put("firedAt", firedAt == null ? "" : TIMESTAMP_FORMAT.format(firedAt));
        return ctx;
    }

    /**
     * 简易占位符替换：遍历 context 将 {key} 替换为对应字符串。null 值替换为空串。
     *
     * @param template 含 {key} 占位符的模板
     * @param context  渲染上下文
     * @return 渲染后的字符串
     */
    static String render(String template, Map<String, Object> context) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        String result = template;
        for (Map.Entry<String, Object> entry : context.entrySet()) {
            String key = "{" + entry.getKey() + "}";
            String value = entry.getValue() == null ? "" : entry.getValue().toString();
            result = result.replace(key, value);
        }
        return result;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
