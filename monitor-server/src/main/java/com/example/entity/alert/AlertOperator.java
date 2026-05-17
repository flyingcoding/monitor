package com.example.entity.alert;

/**
 * 告警比较运算符枚举。{@link #test(Double, Double)} 用于阈值评估，null 安全返回 false。
 */
public enum AlertOperator {
    GT("gt"),
    LT("lt"),
    GTE("gte"),
    LTE("lte");

    private final String column;

    AlertOperator(String column) {
        this.column = column;
    }

    /**
     * 返回与 {@code alert_rule.operator} 列一致的字符串。
     *
     * @return 列字符串
     */
    public String getColumn() {
        return column;
    }

    /**
     * 评估当前值是否满足该运算符相对于阈值的条件。任一参数为 null 时返回 false。
     *
     * @param current   当前指标值
     * @param threshold 阈值
     * @return 满足条件返回 true
     */
    public boolean test(Double current, Double threshold) {
        if (current == null || threshold == null) {
            return false;
        }
        return switch (this) {
            case GT -> current > threshold;
            case LT -> current < threshold;
            case GTE -> current >= threshold;
            case LTE -> current <= threshold;
        };
    }

    /**
     * 根据列字符串反向查找枚举；找不到返回 null。
     *
     * @param column 列字符串
     * @return 枚举或 null
     */
    public static AlertOperator fromColumn(String column) {
        if (column == null) {
            return null;
        }
        for (AlertOperator op : values()) {
            if (op.column.equals(column)) {
                return op;
            }
        }
        return null;
    }
}
