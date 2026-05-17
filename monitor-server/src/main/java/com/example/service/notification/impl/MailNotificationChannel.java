package com.example.service.notification.impl;

import com.example.entity.alert.AlertEvent;
import com.example.service.notification.NotificationChannelSender;
import jakarta.annotation.Resource;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 邮件通知通道实现。复用 Spring Boot 提供的 JavaMailSender，发送 HTML 中文邮件。
 * <p>
 * 配置字段（来自 notification_channel.config JSON）：
 * <ul>
 *   <li>{@code to_addrs}：String（逗号分隔）或 {@code List<String>}，收件人列表。</li>
 *   <li>{@code subject_template}（可选）：邮件主题模板，未配置时使用默认主题。</li>
 *   <li>{@code body_template}（可选）：邮件正文模板（HTML），未配置时使用内置中文模板。</li>
 * </ul>
 * 模板占位符遵循 {@link NotificationFormat#render(String, Map)} 约定。
 */
@Slf4j
@Component
public class MailNotificationChannel implements NotificationChannelSender {

    private static final String DEFAULT_SUBJECT_TEMPLATE = "[{levelLabel}] {clientName} {metricLabel} 告警";

    private static final String DEFAULT_BODY_TEMPLATE = """
            <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;">
              <h3 style="color:#d9534f;">[{levelLabel}] {clientName}</h3>
              <table style="border-collapse: collapse; line-height: 1.6;">
                <tr><td style="color:#666; padding-right:12px;">指标</td><td>{metricLabel}</td></tr>
                <tr><td style="color:#666; padding-right:12px;">当前值</td><td><b>{currentValue}</b></td></tr>
                <tr><td style="color:#666; padding-right:12px;">阈值</td><td>{threshold}</td></tr>
                <tr><td style="color:#666; padding-right:12px;">触发时间</td><td>{firedAt}</td></tr>
              </table>
              <p style="color:#444; margin-top:16px;">{message}</p>
            </div>
            """;

    @Resource
    private JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String fromAddress;

    @Override
    public String type() {
        return "mail";
    }

    /**
     * 发送告警邮件：解析收件人列表、渲染主题与 HTML 正文、调用 JavaMailSender 投递。
     *
     * @param event  告警事件
     * @param config 通道配置（敏感字段已解密）
     * @throws Exception 投递失败时抛出，交由 RabbitMQ 重试 / DLX 处理
     */
    @Override
    public void send(AlertEvent event, Map<String, Object> config) throws Exception {
        List<String> recipients = parseRecipients(config.get("to_addrs"));
        if (recipients.isEmpty()) {
            throw new IllegalArgumentException("邮件通知缺少 to_addrs 配置");
        }

        Map<String, Object> context = NotificationFormat.buildContext(event);
        String subjectTemplate = stringConfig(config, "subject_template", DEFAULT_SUBJECT_TEMPLATE);
        String bodyTemplate = stringConfig(config, "body_template", DEFAULT_BODY_TEMPLATE);
        String subject = NotificationFormat.render(subjectTemplate, context);
        String body = NotificationFormat.render(bodyTemplate, context);

        MimeMessage mime = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mime, false, StandardCharsets.UTF_8.name());
        try {
            if (fromAddress != null && !fromAddress.isEmpty()) {
                helper.setFrom(fromAddress);
            }
            helper.setTo(recipients.toArray(new String[0]));
            helper.setSubject(subject);
            helper.setText(body, true);
        } catch (MessagingException e) {
            throw new IllegalStateException("构建邮件失败", e);
        }
        mailSender.send(mime);
        log.info("发送邮件告警成功，clientId={}, recipients={}", event.getClientId(), recipients.size());
    }

    /**
     * 解析 {@code to_addrs} 配置：支持单个字符串（逗号分隔）或字符串集合。
     *
     * @param raw 原始配置值
     * @return 去空白与空项后的收件人列表
     */
    private static List<String> parseRecipients(Object raw) {
        List<String> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        if (raw instanceof Collection<?> coll) {
            for (Object item : coll) {
                if (item == null) {
                    continue;
                }
                String s = item.toString().trim();
                if (!s.isEmpty()) {
                    result.add(s);
                }
            }
            return result;
        }
        String text = raw.toString();
        for (String part : text.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * 读取字符串配置，未配置 / 为空时返回默认值。
     *
     * @param config       配置 Map
     * @param key          配置键
     * @param defaultValue 默认值
     * @return 配置值或默认值
     */
    private static String stringConfig(Map<String, Object> config, String key, String defaultValue) {
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = value.toString();
        return text.isEmpty() ? defaultValue : text;
    }
}
