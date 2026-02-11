package com.example.listener;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RabbitListener(queues = "mail")
public class MailQueueListener {

    private static final int MAX_RETRY = 3;

    @Resource
    JavaMailSender sender;

    @Value("${spring.mail.username}")
    String username;

    @RabbitHandler
    public void sendMailMessage(Map<String, Object> data, Message amqpMessage) {
        String email = data.get("email").toString();
        Integer code = (Integer) data.get("code");

        // 使用 AMQP message properties 获取重投递状态
        Boolean redelivered = amqpMessage.getMessageProperties().getRedelivered();
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();

        SimpleMailMessage message = switch (data.get("type").toString()) {
            case "reset" ->
                    createMessage("您的密码重置邮件",
                            "你好，您正在执行重置密码操作，验证码: " + code + "，有效时间3分钟，如非本人操作，请无视。",
                            email);
            case "modify" ->
                    createMessage("您的邮箱修改邮件",
                            "你好，您正在绑定新的邮箱，验证码: " + code + "，有效时间3分钟，如非本人操作，请无视。",
                            email);
            default -> null;
        };
        if (message == null) return;
        try {
            sender.send(message);
        } catch (Exception e) {
            log.error("邮件发送失败 (redelivered={}): {}", redelivered, e.getMessage());
            // 抛出异常让 RabbitMQ reject 并通过 DLX 路由到死信队列
            throw new RuntimeException("邮件发送失败", e);
        }
    }

    private SimpleMailMessage createMessage(String title, String content, String email) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setSubject(title);
        message.setText(content);
        message.setTo(email);
        message.setFrom(username);
        return message;
    }
}
