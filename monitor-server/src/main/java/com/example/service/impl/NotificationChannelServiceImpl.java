package com.example.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.entity.dto.AlertRule;
import com.example.entity.dto.NotificationChannel;
import com.example.mapper.AlertRuleMapper;
import com.example.mapper.NotificationChannelMapper;
import com.example.service.NotificationChannelService;
import com.example.utils.CryptoUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 通知通道 Service 实现。负责对 config 中 _enc 后缀的敏感字段进行加解密管理，
 * 以及在删除前检查 alert_rule.channel_ids 是否仍有引用，避免破坏告警规则。
 */
@Slf4j
@Service
public class NotificationChannelServiceImpl
        extends ServiceImpl<NotificationChannelMapper, NotificationChannel>
        implements NotificationChannelService {

    private static final String ENC_SUFFIX = "_enc";
    private static final String MASK_PLACEHOLDER = "***";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Resource
    private AlertRuleMapper alertRuleMapper;

    @Resource
    private CryptoUtils cryptoUtils;

    /**
     * 检查指定通道是否被任意告警规则的 channel_ids JSON 数组引用。
     * <p>
     * MySQL 8 的 {@code JSON_CONTAINS(json_doc, candidate)} 在 candidate 为标量数字时
     * 必须显式 CAST 为 JSON；channel_ids 列为 NULL 时返回 NULL（视为不匹配）。
     *
     * @param channelId 通道ID
     * @return 仍有规则引用时返回 true
     */
    public boolean isReferenced(Long channelId) {
        if (channelId == null) {
            return false;
        }
        Long count = alertRuleMapper.selectCount(new QueryWrapper<AlertRule>()
                .isNotNull("channel_ids")
                .apply("JSON_CONTAINS(channel_ids, CAST({0} AS JSON))", channelId.toString()));
        return count != null && count > 0;
    }

    /**
     * 将 config 中所有以 {@code _enc} 结尾的明文字段就地替换为密文。
     * <p>
     * 已经被 {@link CryptoUtils#encrypt(String)} 加密过的值会被识别（带 {@code ENC:} 前缀），
     * 重复调用是幂等的。遮罩占位符 {@value #MASK_PLACEHOLDER} 不会被加密。
     * <p>
     * 非 String 类型的值（如 Map / List）会先用 Jackson 序列化为 JSON 字符串再加密，
     * 解密侧需用同样的 ObjectMapper 反序列化为原结构。这样 webhook 的 {@code headers_enc}
     * 等结构化敏感字段也能整体加密，避免逐键标记 _enc 的繁琐。
     *
     * @param config 通道配置 Map
     */
    public void encryptSensitive(Map<String, Object> config) {
        if (config == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.endsWith(ENC_SUFFIX)) {
                continue;
            }
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (value instanceof String str) {
                if (str.isEmpty() || MASK_PLACEHOLDER.equals(str)) {
                    continue;
                }
                entry.setValue(cryptoUtils.encrypt(str));
            } else {
                // 非字符串值：先 JSON 序列化为字符串再加密；解密侧负责反序列化
                try {
                    String serialized = OBJECT_MAPPER.writeValueAsString(value);
                    entry.setValue(cryptoUtils.encrypt(serialized));
                } catch (Exception e) {
                    log.warn("通知通道字段 {} 序列化失败，跳过加密", key);
                }
            }
        }
    }

    /**
     * 更新场景下，将 newConfig 中仍为遮罩占位符 {@value #MASK_PLACEHOLDER} 的 {@code _enc} 字段
     * 用 oldConfig 中已加密的旧值回填，避免前端未修改的敏感字段被覆盖。
     *
     * @param newConfig 新提交的 config
     * @param oldConfig 数据库中已存的 config（含密文）
     */
    public void preserveExistingEnc(Map<String, Object> newConfig, Map<String, Object> oldConfig) {
        if (newConfig == null || oldConfig == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : newConfig.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.endsWith(ENC_SUFFIX)) {
                continue;
            }
            if (MASK_PLACEHOLDER.equals(entry.getValue()) && oldConfig.containsKey(key)) {
                entry.setValue(oldConfig.get(key));
            }
        }
    }

    /**
     * 对 config 中所有 _enc 后缀字段执行解密，返回新 Map（不修改原 Map）。
     * 用于发送测试通知时向 NotificationChannelSender 传递解密后的明文配置。
     *
     * @param config 含密文的 config
     * @return 解密后的 config 副本，原 Map 为 null 时返回 null
     */
    public Map<String, Object> decryptSensitive(Map<String, Object> config) {
        if (config == null) {
            return null;
        }
        Map<String, Object> decrypted = new LinkedHashMap<>(config.size());
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key != null && key.endsWith(ENC_SUFFIX) && value instanceof String str && !str.isEmpty()) {
                try {
                    decrypted.put(key, cryptoUtils.decrypt(str));
                } catch (Exception e) {
                    log.warn("通知通道敏感字段 {} 解密失败，使用原值占位", key);
                    decrypted.put(key, str);
                }
            } else {
                decrypted.put(key, value);
            }
        }
        return decrypted;
    }

    /**
     * 判断两个 config 是否在结构上等价（仅用于审计场景，未在主流程使用）。
     *
     * @param a config A
     * @param b config B
     * @return 等价返回 true
     */
    public boolean configEquals(Map<String, Object> a, Map<String, Object> b) {
        return Objects.equals(a, b);
    }
}
