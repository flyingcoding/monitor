package com.example.tsdb;

import java.time.Duration;

/**
 * 时序后端历史查询通用工具方法（v2.0 PR1 引入）。
 *
 * <p>历史曲线 API 支持 1h–7d 任意时间窗口，原始 10s 采样在 7d 视图下会产生 ~36 万点
 * （JSON 约 18MB），既无法直传也不能在浏览器渲染。该工具按窗口长度选择服务端
 * 聚合 step，使单次返回稳定在 1k-2k 点；具体决策见 PRD §D4。
 *
 * <h3>step 选择规则</h3>
 * <pre>
 *   window ≤ 1h   → 10s   （原生分辨率，无聚合）
 *   window ≤ 6h   → 30s   （12× 聚合）
 *   window ≤ 24h  → 2min  （12×）
 *   window ≤ 7d   → 10min （60× 聚合 → ~1000 点）
 *   其他          → ceil(window / 1500) 秒，保底 ≤ 2000 点
 * </pre>
 *
 * <p>该类同时被 {@link InfluxDbProvider}（aggregateWindow {@code every}）与
 * {@link VictoriaMetricsProvider}（PromQL {@code step}）复用，保证两个 provider
 * 的下采样口径一致，前端图表无需感知 provider 切换。
 */
public final class TsdbQueryUtils {

    /** 1h 窗口的临界 step：10s 原生分辨率。 */
    public static final Duration STEP_10S = Duration.ofSeconds(10);

    /** 6h 窗口的 step：30s。 */
    public static final Duration STEP_30S = Duration.ofSeconds(30);

    /** 24h 窗口的 step：2min。 */
    public static final Duration STEP_2M = Duration.ofMinutes(2);

    /** 7d 窗口的 step：10min。 */
    public static final Duration STEP_10M = Duration.ofMinutes(10);

    /** 超过 7d 时的兜底点数上限；step = ceil(window.toSeconds() / MAX_POINTS)。 */
    public static final int MAX_POINTS_FALLBACK = 1500;

    /** 兜底 step 的最小值；防止极短异常窗口（如 0）产生 0 秒 step。 */
    public static final Duration MIN_FALLBACK_STEP = Duration.ofSeconds(1);

    private TsdbQueryUtils() {
        // 工具类禁止实例化
    }

    /**
     * 根据时间窗口选择服务端聚合 step。
     *
     * <p>规则详见类级注释。{@code null} 或非正数 window 视为 1h 窗口，返回 {@link #STEP_10S}。
     *
     * @param window 时间窗口长度
     * @return 聚合 step；保证返回非 null 且 step ≥ 1 秒
     */
    public static Duration chooseStep(Duration window) {
        if (window == null || window.isNegative() || window.isZero()) {
            return STEP_10S;
        }
        long seconds = window.toSeconds();
        if (seconds <= Duration.ofHours(1).toSeconds()) {
            return STEP_10S;
        }
        if (seconds <= Duration.ofHours(6).toSeconds()) {
            return STEP_30S;
        }
        if (seconds <= Duration.ofHours(24).toSeconds()) {
            return STEP_2M;
        }
        if (seconds <= Duration.ofDays(7).toSeconds()) {
            return STEP_10M;
        }
        // ceil(seconds / MAX_POINTS_FALLBACK) 秒，保底 1 秒
        long fallbackSeconds = (seconds + MAX_POINTS_FALLBACK - 1) / MAX_POINTS_FALLBACK;
        Duration fallback = Duration.ofSeconds(fallbackSeconds);
        return fallback.compareTo(MIN_FALLBACK_STEP) < 0 ? MIN_FALLBACK_STEP : fallback;
    }
}
