package com.example.entity.vo.response;

import lombok.Data;

import java.util.List;

/**
 * 公开状态页客户端汇总条目。
 *
 * <h3>严格排除敏感字段</h3>
 * <p>本 VO 不包含 id（仅暴露 displayName）、内部 {@code name}、IP、CPU/内存/磁盘/网络指标、
 * OS 名称版本、agent 元数据等。所有取自内部 {@code Client} / {@code RuntimeDetailVO} 的字段
 * 必须在 {@code StatusPageService.toClientVO} 中显式映射；新增内部字段不会自动泄露到此处。
 */
@Data
public class StatusPageClientVO {

    /**
     * 客户端公开显示名（{@code client.display_name}）；当未配置 displayName 时回退到 {@code client.name}。
     */
    String displayName;

    /**
     * 当前是否在线（依据现有 {@code ClientService.isClientOnline} 心跳/运行时窗口判定）。
     */
    boolean online;

    /**
     * 最近 24 小时可用率（0.0..1.0）。当客户端在过去 24h 完全无数据点时为 {@code null}（前端展示"数据不足"）。
     */
    Double availability24h;

    /**
     * 48 个桶 × 30 分钟可用率序列（oldest → newest）。每个值要么是 1.0（在线）要么 0.0（离线）。
     * 当 {@link #availability24h} 为 {@code null} 时本列表为空（前端按"数据不足"渲染）。
     */
    List<Double> buckets;

    /**
     * 距最近一次见到该客户端的秒数；从未上线则为 {@code null}。
     */
    Long lastSeenSecondsAgo;
}
