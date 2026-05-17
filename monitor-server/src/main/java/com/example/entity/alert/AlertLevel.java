package com.example.entity.alert;

/**
 * 告警等级枚举。
 */
public enum AlertLevel {
    INFO("info"),
    WARNING("warning"),
    CRITICAL("critical");

    private final String column;

    AlertLevel(String column) {
        this.column = column;
    }

    /**
     * 返回与 {@code alert_rule.level} / {@code alert_history.level} 列一致的字符串。
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
    public static AlertLevel fromColumn(String column) {
        if (column == null) {
            return null;
        }
        for (AlertLevel level : values()) {
            if (level.column.equals(column)) {
                return level;
            }
        }
        return null;
    }
}
