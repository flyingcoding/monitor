package com.example.entity.alert;

/**
 * 告警支持的指标类型枚举。
 * <p>
 * 每个枚举对应 {@code RuntimeDetailVO} 中的一个数值字段，{@link #getColumn()} 返回与
 * Flyway V2 中 {@code alert_rule.metric} 列约束一致的 lowercase snake_case 名称。
 */
public enum AlertMetric {
    CPU("cpu"),
    MEMORY("memory"),
    DISK("disk"),
    NETWORK_UP("network_up"),
    NETWORK_DOWN("network_down");

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
