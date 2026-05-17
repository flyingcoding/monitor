package com.example.entity.dto;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 告警规则实体类。channel_ids 列存 JSON 数组，由 JacksonTypeHandler 在读写时与
 * {@link List} 互转；@TableName(autoResultMap = true) 保证 SELECT 时类型处理器生效。
 * <p>
 * 字段更新策略说明：
 * <ul>
 *   <li>{@code clientId}：用户在 UI 上把"绑定客户端"下拉清空表示将规则改回"全局规则"，
 *       业务上需要写 null 进 DB；标 {@link FieldStrategy#ALWAYS} 让 MyBatis-Plus
 *       的 {@code updateById} 即便实体字段为 null 也强制 SET。</li>
 *   <li>{@code channelIds}：用户在 UI 上清空"通知通道"多选框是合法操作，需要把 JSON
 *       数组写为 null/空；与 {@code clientId} 同样使用 ALWAYS。</li>
 *   <li>{@code silenceUntil}：业务上"普通编辑规则"不应清空静默期（已通过
 *       AlertRuleUpdateVO 移除字段 + MapStruct ignore 双重保证）。这里 <b>不要</b> 启用
 *       ALWAYS，否则任何 {@code updateById} 都会把已设定的静默期一并清空。</li>
 *   <li>其余字段沿用 MyBatis-Plus 默认 {@link FieldStrategy#NOT_NULL}：值为 null 时
 *       跳过 SET，避免普通更新意外清空。</li>
 * </ul>
 */
@Data
@TableName(value = "alert_rule", autoResultMap = true)
public class AlertRule {
    @TableId(type = IdType.AUTO)
    Long id;
    String name;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    Integer clientId;
    String metric;
    String operator;
    Double threshold;
    Integer durationSec;
    String level;
    Boolean enabled;
    @TableField(typeHandler = JacksonTypeHandler.class, updateStrategy = FieldStrategy.ALWAYS)
    List<Long> channelIds;
    Date silenceUntil;
    Date createdAt;
    Date updatedAt;
}
