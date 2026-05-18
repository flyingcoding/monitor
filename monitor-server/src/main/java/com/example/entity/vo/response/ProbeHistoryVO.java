package com.example.entity.vo.response;

import lombok.Data;

import java.util.Date;

/**
 * 服务探测历史响应 VO，对应 {@code probe_history} 表的一行。
 */
@Data
public class ProbeHistoryVO {

    private Long id;
    private Long taskId;
    private Date executedAt;
    private Boolean success;
    private Integer latencyMs;
    private Integer statusCode;
    private Integer sslDaysRemaining;
    private String errorMessage;
}
