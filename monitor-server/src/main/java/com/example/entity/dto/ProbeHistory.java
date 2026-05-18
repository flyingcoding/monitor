package com.example.entity.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 服务探测历史记录实体。对应 v1.3 Flyway V4 中的 {@code probe_history} 表。
 *
 * <p>每次探测一行：success 标志、延迟、状态码、SSL 剩余天、失败原因。
 * 30 天滚动清理由 {@link com.example.service.impl.ProbeHistoryCleanupJob} 完成。
 */
@Data
@TableName("probe_history")
public class ProbeHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联的 probe_task.id。 */
    private Long taskId;

    /** 探测执行时间。 */
    private Date executedAt;

    /** 是否成功（1=成功，0=失败）。 */
    private Boolean success;

    /** 延迟（毫秒）；成功探测才有意义，失败时可能为 null 或测得的失败前耗时。 */
    private Integer latencyMs;

    /** HTTP 状态码；仅 HTTP 探测有效。 */
    private Integer statusCode;

    /** SSL 证书剩余天数；仅 HTTPS 探测有效。 */
    private Integer sslDaysRemaining;

    /** 失败原因（简短），成功时为 null。 */
    private String errorMessage;
}
