package com.example.entity.alert;

/**
 * 告警生命周期状态枚举。firing → resolved 由 AlertEvaluator 自动流转；
 * firing → acknowledged 由用户在前端确认触发。
 */
public enum AlertStatus {
    FIRING("firing"),
    RESOLVED("resolved"),
    ACKNOWLEDGED("acknowledged");

    private final String column;

    AlertStatus(String column) {
        this.column = column;
    }

    /**
     * 返回与 {@code alert_history.status} 列一致的字符串。
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
    public static AlertStatus fromColumn(String column) {
        if (column == null) {
            return null;
        }
        for (AlertStatus status : values()) {
            if (status.column.equals(column)) {
                return status;
            }
        }
        return null;
    }
}
