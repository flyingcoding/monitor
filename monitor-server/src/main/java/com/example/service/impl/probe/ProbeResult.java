package com.example.service.impl.probe;

import lombok.Builder;
import lombok.Data;

/**
 * 单次探测结果。
 *
 * <p>由各 {@link ProbeExecutor} 实现产生，{@link com.example.service.impl.ProbeScheduler} 据此：
 * <ul>
 *   <li>写入 {@code probe_history}（success / latency / statusCode / sslDaysRemaining / errorMessage）；</li>
 *   <li>累计连续失败计数；</li>
 *   <li>SSL 即将过期时直接触发"提前预警"分支；</li>
 *   <li>达到 {@code consecutive_failures_threshold} 时投递 AlertEvent。</li>
 * </ul>
 */
@Data
@Builder
public class ProbeResult {

    /** 探测是否成功（true = 业务上成功 / 满足期望）。 */
    private boolean success;

    /** 探测耗时（毫秒）；失败时也可以记录失败前耗时。 */
    private Integer latencyMs;

    /** HTTP 状态码；仅 HTTP 探测有意义。 */
    private Integer statusCode;

    /** SSL 证书剩余天数；仅 HTTPS 探测有意义。 */
    private Integer sslDaysRemaining;

    /** 失败原因（中文简述），成功时通常为 null。 */
    private String errorMessage;

    /**
     * SSL 证书剩余天 ≤ {@code probe_task.ssl_warn_days} 时本字段为 true，
     * Scheduler 据此独立触发"即将过期"告警（无需累计失败次数）。
     */
    private boolean sslExpiringSoon;
}
