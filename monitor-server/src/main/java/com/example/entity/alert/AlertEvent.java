package com.example.entity.alert;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 告警事件 DTO：AlertEvaluator 触发告警后投递到 RabbitMQ "notification" 队列，
 * NotificationQueueListener 消费后路由到各 NotificationChannelSender 实现完成发送。
 * <p>
 * 实现 Serializable 以便 Spring AMQP 默认消息转换器序列化。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertEvent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 告警规则ID。 */
    private Long ruleId;
    /** 已落库的 alert_history 记录ID。 */
    private Long historyId;
    /** 触发告警的客户端ID。 */
    private Integer clientId;
    /** 客户端名称（冗余，便于通知模板渲染）。 */
    private String clientName;
    /** 指标列字符串，与 {@link AlertMetric#getColumn()} 一致。 */
    private String metric;
    /** 比较运算符列字符串，与 {@link AlertOperator#getColumn()} 一致。 */
    private String operator;
    /** 触发阈值。 */
    private Double threshold;
    /** 触发时的当前指标值。 */
    private Double currentValue;
    /** 告警等级列字符串，与 {@link AlertLevel#getColumn()} 一致。 */
    private String level;
    /** 通知文案。 */
    private String message;
    /** 触发时间。 */
    private LocalDateTime firedAt;
    /** 需要送达的通知通道ID列表；NotificationQueueListener 据此路由。 */
    private List<Long> channelIds;
}
