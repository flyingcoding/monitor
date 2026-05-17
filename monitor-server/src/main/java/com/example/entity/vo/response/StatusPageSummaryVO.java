package com.example.entity.vo.response;

import lombok.Data;

import java.util.List;

/**
 * 公开状态页 {@code GET /api/status/summary} 响应 VO。
 *
 * <h3>敏感字段严格白名单</h3>
 * <p>本 VO 故意不暴露 IP / CPU / 内存 / 磁盘 / 网络 / OS / 内部 {@code name} 等任何主机敏感字段（PRD R26）。
 * 调用方仅能拿到：
 * <ul>
 *   <li>状态页元信息：{@code title / subtitle / brandColor / logoUrl}</li>
 *   <li>整体可用率聚合：{@code overallAvailability}（最近 24h 在线桶占比）</li>
 *   <li>客户端列表：{@link StatusPageClientVO}（仅 displayName / online / availability24h / buckets / lastSeenSecondsAgo）</li>
 * </ul>
 */
@Data
public class StatusPageSummaryVO {

    String title;

    String subtitle;

    String brandColor;

    String logoUrl;

    /**
     * 整体可用率：所有公开客户端最近 24h 在线桶占比的算术平均（0.0..1.0）；
     * 当公开客户端列表为空时为 {@code null}。
     */
    Double overallAvailability;

    /**
     * 服务端生成时间戳（毫秒）。前端可用于"上次刷新于 N 秒前"提示，与 SSE 替代用途配套。
     */
    long generatedAt;

    /**
     * 公开客户端汇总列表。
     */
    List<StatusPageClientVO> clients;
}
