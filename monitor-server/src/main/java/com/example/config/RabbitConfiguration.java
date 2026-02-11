package com.example.config;

import org.springframework.amqp.core.*;
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
}
