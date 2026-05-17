package com.example.service.notification;

import com.example.entity.alert.AlertEvent;
import com.example.service.notification.impl.MailNotificationChannel;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MailNotificationChannel 单元测试。使用 JDK 动态代理桩替代 Mockito，
 * 符合项目惯例（参考 ClientControllerTest / AlertEvaluatorImplTest）。
 */
class MailNotificationChannelTest {

    private MailNotificationChannel channel;
    private final AtomicReference<MimeMessage> sentMessage = new AtomicReference<>();

    /**
     * 装配 channel 实例 + 桩 JavaMailSender；createMimeMessage 返回真实 MimeMessage 以便断言。
     */
    @BeforeEach
    void setUp() {
        channel = new MailNotificationChannel();
        sentMessage.set(null);
        JavaMailSender sender = (JavaMailSender) Proxy.newProxyInstance(
                JavaMailSender.class.getClassLoader(),
                new Class[]{JavaMailSender.class},
                new MailSenderStub(sentMessage)
        );
        ReflectionTestUtils.setField(channel, "mailSender", sender);
        ReflectionTestUtils.setField(channel, "fromAddress", "alert@example.com");
    }

    /**
     * 验证默认主题与正文模板渲染中文指标名与告警字段。
     */
    @Test
    void shouldRenderDefaultSubjectAndBody() throws Exception {
        AlertEvent event = AlertEvent.builder()
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
        Map<String, Object> config = new HashMap<>();
        config.put("to_addrs", "ops@example.com, dev@example.com");

        channel.send(event, config);

        MimeMessage mime = sentMessage.get();
        Assertions.assertNotNull(mime);
        Assertions.assertEquals("[警告] web-01 CPU 使用率 告警", mime.getSubject());
        Address[] recipients = mime.getRecipients(Message.RecipientType.TO);
        Assertions.assertNotNull(recipients);
        Assertions.assertEquals(2, recipients.length);
        Assertions.assertEquals("ops@example.com", ((InternetAddress) recipients[0]).getAddress());
        Assertions.assertEquals("dev@example.com", ((InternetAddress) recipients[1]).getAddress());

        String body = bodyAsString(mime);
        Assertions.assertTrue(body.contains("CPU 使用率"), "正文应含中文指标名");
        Assertions.assertTrue(body.contains("web-01"), "正文应含客户端名");
        Assertions.assertTrue(body.contains("0.91"), "正文应含当前值");
        Assertions.assertTrue(body.contains("0.8"), "正文应含阈值");
        Assertions.assertTrue(body.contains("2026-05-17 01:02:03"), "正文应含触发时间");
        Assertions.assertTrue(body.contains("CPU 持续过高"), "正文应含 message");
    }

    /**
     * 验证支持 List 形式的收件人。
     */
    @Test
    void shouldAcceptListRecipients() throws Exception {
        AlertEvent event = baseEvent();
        Map<String, Object> config = new HashMap<>();
        config.put("to_addrs", List.of("a@example.com", "b@example.com"));

        channel.send(event, config);

        Address[] recipients = sentMessage.get().getRecipients(Message.RecipientType.TO);
        Assertions.assertNotNull(recipients);
        Assertions.assertEquals(2, recipients.length);
    }

    /**
     * 自定义模板生效：subject_template / body_template 优先使用用户配置。
     */
    @Test
    void shouldUseCustomTemplates() throws Exception {
        AlertEvent event = baseEvent();
        Map<String, Object> config = new HashMap<>();
        config.put("to_addrs", "ops@example.com");
        config.put("subject_template", "ALERT: {clientName} - {metric}");
        config.put("body_template", "Custom {currentValue} vs {threshold}");

        channel.send(event, config);

        MimeMessage mime = sentMessage.get();
        Assertions.assertEquals("ALERT: web-01 - cpu", mime.getSubject());
        Assertions.assertTrue(bodyAsString(mime).contains("Custom 0.91 vs 0.8"));
    }

    /**
     * 缺少 to_addrs 应抛出 IllegalArgumentException。
     */
    @Test
    void shouldFailWhenRecipientsMissing() {
        AlertEvent event = baseEvent();
        Map<String, Object> config = new HashMap<>();
        Assertions.assertThrows(IllegalArgumentException.class, () -> channel.send(event, config));
    }

    /**
     * 通道类型标识。
     */
    @Test
    void typeShouldReturnMail() {
        Assertions.assertEquals("mail", channel.type());
    }

    /**
     * 提取 MimeMessage 的字符串正文（multipart 取第一个 part）。
     */
    private static String bodyAsString(MimeMessage mime) throws Exception {
        Object content = mime.getContent();
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof jakarta.mail.Multipart mp) {
            jakarta.mail.BodyPart part = mp.getBodyPart(0);
            Object inner = part.getContent();
            if (inner instanceof jakarta.mail.Multipart mpInner) {
                return mpInner.getBodyPart(0).getContent().toString();
            }
            return inner.toString();
        }
        return content == null ? "" : content.toString();
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

    /**
     * JavaMailSender 桩：createMimeMessage 返回真实 MimeMessage 用于断言；
     * send(MimeMessage) 缓存调用参数；其它方法抛出异常以暴露未预期调用。
     */
    private static class MailSenderStub implements java.lang.reflect.InvocationHandler {
        private final AtomicReference<MimeMessage> captured;

        MailSenderStub(AtomicReference<MimeMessage> captured) {
            this.captured = captured;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("createMimeMessage".equals(name)) {
                Session session = Session.getInstance(new Properties());
                return new MimeMessage(session);
            }
            if ("send".equals(name) && args != null && args.length > 0 && args[0] instanceof MimeMessage mime) {
                captured.set(mime);
                return null;
            }
            if ("toString".equals(name)) {
                return "MailSenderStub";
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return proxy == args[0];
            }
            throw new UnsupportedOperationException("测试桩未实现方法: " + name);
        }
    }
}
