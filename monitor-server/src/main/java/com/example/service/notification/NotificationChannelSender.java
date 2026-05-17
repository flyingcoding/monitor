package com.example.service.notification;

import com.example.entity.alert.AlertEvent;

import java.util.Map;

/**
 * 通知通道发送器抽象。每种通道类型（mail / webhook / dingtalk / feishu）由独立实现
 * 提供，并通过 {@link #type()} 在 NotificationQueueListener 中按 channel.type 路由。
 * <p>
 * 实现类应当：
 * <ul>
 *   <li>从传入的 {@code config} 中读取通道配置；带 {@code _enc} 后缀的字段由调用方
 *       使用 {@code CryptoUtils} 提前解密。</li>
 *   <li>对接外部服务时使用现有 {@code NetUtils.doPost} 或 {@code RestTemplate}，不引入新 HTTP 库。</li>
 *   <li>失败时抛出异常，交由 RabbitMQ DLX 机制重试或落入死信队列。</li>
 * </ul>
 */
public interface NotificationChannelSender {

    /**
     * 通道类型标识，与 {@code notification_channel.type} 列保持一致。
     * 例如：{@code "mail"} / {@code "webhook"} / {@code "dingtalk"} / {@code "feishu"}。
     *
     * @return 类型字符串
     */
    String type();

    /**
     * 发送告警通知。
     *
     * @param event  告警事件
     * @param config 通道配置（敏感字段已解密为明文）
     * @throws Exception 发送失败抛出，由调用方决定重试 / 死信处理
     */
    void send(AlertEvent event, Map<String, Object> config) throws Exception;
}
