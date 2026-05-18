package com.example.entity.alert;

/**
 * 告警支持的指标类型枚举。
 * <p>
 * 每个枚举对应 {@code RuntimeDetailVO} 中的一个数值字段，{@link #getColumn()} 返回与
 * Flyway V2 / V4 中 {@code alert_rule.metric} 列约束一致的 lowercase snake_case 名称。
 * <p>
 * v1.3 新增 4 项聚合 metric：GPU 最高温度 / SMART 关键异常数 / 失败 systemd 服务数 / 关键进程缺失数。
 * 客户端无对应采集能力时上报 null，{@link com.example.service.impl.AlertEvaluatorImpl} 自动跳过规则。
 */
public enum AlertMetric {
    CPU("cpu"),
    MEMORY("memory"),
    DISK("disk"),
    NETWORK_UP("network_up"),
    NETWORK_DOWN("network_down"),
    /** v1.3：当前主机所有 GPU 中的最高温度（℃）。 */
    GPU_TEMPERATURE_MAX("gpu_temperature_max"),
    /** v1.3：SMART 关键异常计数（reallocated/pending/uncorrectable/media_errors 之和 > 0 的设备数）。 */
    SMART_CRITICAL_COUNT("smart_critical_count"),
    /** v1.3：失败 systemd 服务数（非 active 的 watched unit 个数）。 */
    SYSTEMD_FAILED_COUNT("systemd_failed_count"),
    /** v1.3：关键进程缺失数（未匹配到的 process pattern 个数）。 */
    WATCHED_PROCESS_MISSING("watched_process_missing");

    private final String column;

    AlertMetric(String column) {
        this.column = column;
    }

    /**
     * 返回与数据库列 {@code alert_rule.metric} 一致的字符串。
     *
     * @return 列字符串
     */
    public String getColumn() {
        return column;
    }

    /**
     * 根据列字符串反向查找枚举；找不到返回 null。
     *
     * @param column 列字符串
     * @return 枚举或 null
     */
    public static AlertMetric fromColumn(String column) {
        if (column == null) {
            return null;
        }
        for (AlertMetric m : values()) {
            if (m.column.equals(column)) {
                return m;
            }
        }
        return null;
    }
}
