package com.example.listener;

import com.example.entity.alert.AlertEvent;
import com.example.entity.dto.NotificationChannel;
import com.example.mapper.NotificationChannelMapper;
import com.example.service.notification.NotificationChannelSender;
import com.example.utils.CryptoUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警通知消费者：消费 {@code notification} 队列中的 {@link AlertEvent}，
 * 根据事件指定的 channelIds 加载 {@code notification_channel} 配置并路由到对应 {@link NotificationChannelSender}。
 * <p>
 * 单条通知失败不影响其他通道，记录 ERROR 日志；整条消息消费过程中若全部通道都已尝试，
 * 仍向上游抛出 RuntimeException 以触发 DLX 死信路由的策略仅在所有通道均失败时启用，
 * 否则视为部分成功并 ACK。
 * <p>
 * 消息转换器使用专属 {@code notificationMessageConverter}（Jackson），与默认 SimpleMessageConverter
 * 解耦，避免破坏 mail 队列的现有 Map 消息流。
 */
@Slf4j
@Component
@RabbitListener(queues = "notification", messageConverter = "notificationMessageConverter")
public class NotificationQueueListener {

    @Resource
    private List<NotificationChannelSender> senders;

    @Resource
    private NotificationChannelMapper notificationChannelMapper;

    @Resource
    private CryptoUtils cryptoUtils;

    private Map<String, NotificationChannelSender> sendersByType;

    /**
     * 注入完成后构建 type → sender 的查表，避免每次消费时遍历列表。
     */
    @PostConstruct
    public void init() {
        Map<String, NotificationChannelSender> map = new HashMap<>();
        if (senders != null) {
            for (NotificationChannelSender sender : senders) {
                map.put(sender.type(), sender);
            }
        }
        this.sendersByType = map;
        log.info("通知通道发送器初始化完成，已注册类型: {}", map.keySet());
    }

    /**
     * 消费告警事件并按 channelIds 路由发送。
     *
     * @param event 告警事件
     */
    @RabbitHandler
    public void handleAlertEvent(AlertEvent event) {
        if (event == null) {
            log.warn("收到空 AlertEvent，已忽略");
            return;
        }
        List<Long> channelIds = event.getChannelIds();
        if (channelIds == null || channelIds.isEmpty()) {
            log.warn("AlertEvent 无 channelIds，已忽略，clientId={}, ruleId={}", event.getClientId(), event.getRuleId());
            return;
        }

        int total = channelIds.size();
        int success = 0;
        int failure = 0;

        for (Long channelId : channelIds) {
            try {
                NotificationChannel channel = notificationChannelMapper.selectById(channelId);
                if (channel == null) {
                    log.warn("通知通道不存在，已跳过，channelId={}", channelId);
                    failure++;
                    continue;
                }
                if (Boolean.FALSE.equals(channel.getEnabled())) {
                    log.info("通知通道已禁用，跳过发送，channelId={}, name={}, type={}",
                            channelId, channel.getName(), channel.getType());
                    continue;
                }

                NotificationChannelSender sender = sendersByType.get(channel.getType());
                if (sender == null) {
                    log.error("不支持的通知通道类型，channelId={}, name={}, type={}",
                            channelId, channel.getName(), channel.getType());
                    failure++;
                    continue;
                }

                Map<String, Object> decrypted = decryptConfig(channel.getConfig());
                sender.send(event, decrypted);
                success++;
            } catch (Exception e) {
                failure++;
                log.error("通知发送失败，channelId={}, ruleId={}, clientId={}, reason={}",
                        channelId, event.getRuleId(), event.getClientId(), e.getMessage(), e);
            }
        }

        log.info("通知事件处理完成，ruleId={}, clientId={}, total={}, success={}, failure={}",
                event.getRuleId(), event.getClientId(), total, success, failure);

        // 全部失败才抛异常进入 DLX；否则视为部分成功并 ACK
        if (success == 0 && failure == total) {
            throw new RuntimeException("所有通知通道发送均失败，触发 DLX 路由");
        }
    }

    /**
     * 解密 config Map 中所有 {@code _enc} 后缀的字符串字段；其他字段原样保留。
     * 解密后返回的 Map 不修改原配置（避免污染 MyBatis 实体缓存）。
     *
     * @param config 原始配置
     * @return 解密后的副本
     */
    private Map<String, Object> decryptConfig(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>(config.size());
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key != null && key.endsWith("_enc") && value instanceof String text) {
                result.put(key, cryptoUtils.decrypt(text));
            } else {
                result.put(key, value);
            }
        }
        return result;
    }
}
