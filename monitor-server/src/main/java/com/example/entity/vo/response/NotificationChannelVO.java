package com.example.entity.vo.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Setter;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通知通道查询响应VO。config 中带 _enc 后缀的敏感字段在序列化为 JSON 的 {@code config} 字段时
 * 会被遮罩为 {@code "***"}，避免 webhook token / 机器人 key 等凭证外泄。
 * <p>
 * 因 Lombok 自动生成的 {@code getConfig()} 与遮罩逻辑冲突，故 config 字段的 getter 手写并
 * 标记 {@link JsonIgnore}，由 {@link #getMaskedConfig()} 通过 {@link JsonProperty}("config") 接管序列化。
 */
@Setter
public class NotificationChannelVO {
    private Long id;
    private String name;
    private String type;
    private Map<String, Object> config;
    private Boolean enabled;
    private Date createdAt;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    /**
     * 内部访问器，禁止序列化到 JSON。需要原始 config 时通过此方法读取。
     *
     * @return 原始 config（未遮罩）
     */
    @JsonIgnore
    public Map<String, Object> getConfig() {
        return config;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    /**
     * Jackson 序列化使用的 config 副本：所有 _enc 后缀字段值替换为 "***"，避免敏感凭证外泄。
     *
     * @return 遮罩后的 config Map
     */
    @JsonProperty("config")
    public Map<String, Object> getMaskedConfig() {
        if (config == null) {
            return null;
        }
        Map<String, Object> masked = new LinkedHashMap<>(config.size());
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            String key = entry.getKey();
            if (key != null && key.endsWith("_enc")) {
                masked.put(key, "***");
            } else {
                masked.put(key, entry.getValue());
            }
        }
        return masked;
    }
}
