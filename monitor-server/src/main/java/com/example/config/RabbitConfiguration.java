package com.example.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfiguration {

    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange("mail.dlx");
    }

    @Bean
    public Queue dlqQueue() {
        return QueueBuilder
                .durable("mail.dlq")
                .build();
    }

    @Bean
    public Binding dlqBinding(Queue dlqQueue, DirectExchange dlxExchange) {
        return BindingBuilder.bind(dlqQueue).to(dlxExchange).with("mail.dead");
    }

    @Bean("mailQueue")
    public Queue queue() {
        return QueueBuilder
                .durable("mail")
                .withArgument("x-dead-letter-exchange", "mail.dlx")
                .withArgument("x-dead-letter-routing-key", "mail.dead")
                .build();
    }

    // ===== 告警通知队列（v1.1）=====
    // 复用 mail 队列的 DLX 模式：消费失败时通过 DLX 路由到 notification.dlq 死信队列，
    // 避免外部 API 调用阻塞告警触发链路。

    @Bean
    public DirectExchange notificationDlxExchange() {
        return new DirectExchange("notification.dlx");
    }

    @Bean
    public Queue notificationDlqQueue() {
        return QueueBuilder
                .durable("notification.dlq")
                .build();
    }

    @Bean
    public Binding notificationDlqBinding(Queue notificationDlqQueue, DirectExchange notificationDlxExchange) {
        return BindingBuilder.bind(notificationDlqQueue).to(notificationDlxExchange).with("notification.dead");
    }

    @Bean("notificationQueue")
    public Queue notificationQueue() {
        return QueueBuilder
                .durable("notification")
                .withArgument("x-dead-letter-exchange", "notification.dlx")
                .withArgument("x-dead-letter-routing-key", "notification.dead")
                .build();
    }

    /**
     * 通知队列使用的 JSON 消息转换器。AlertEvent 含 LocalDateTime 等 JSR-310 类型，
     * 需要注册 JavaTimeModule；并启用 default typing 信息以便消费侧反序列化为强类型 POJO。
     *
     * @return Jackson2JsonMessageConverter
     */
    @Bean("notificationMessageConverter")
    public MessageConverter notificationMessageConverter() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return new Jackson2JsonMessageConverter(mapper);
    }

    /**
     * 专供告警通知投递使用的 RabbitTemplate，使用 Jackson 消息转换器，
     * 与默认（SimpleMessageConverter，用于 mail 队列的 Map 数据）解耦避免互相影响。
     *
     * @param connectionFactory               连接工厂
     * @param notificationMessageConverter    Jackson 消息转换器
     * @return RabbitTemplate
     */
    @Bean("notificationRabbitTemplate")
    public RabbitTemplate notificationRabbitTemplate(ConnectionFactory connectionFactory,
                                                     MessageConverter notificationMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(notificationMessageConverter);
        return template;
    }
}
