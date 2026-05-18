package com.example.entity.vo.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 客户端实时上报数据 VO。
 * <p>
 * 基础 7 字段（{@code cpuUsage} ~ {@code diskWrite}）由所有客户端必填。
 * v1.3 新增 4 个可选聚合字段：GPU 最高温度 / SMART 关键异常数 / 失败 systemd 服务数 / 关键进程缺失数；
 * 客户端在 {@code application.properties} 未启用对应采集或运行环境缺失工具时上报 {@code null}，
 * {@link com.example.service.impl.AlertEvaluatorImpl#extractMetricValue} 收到 null 时跳过相关规则评估。
 */
@Data
public class RuntimeDetailVO {
    @NotNull
    long timestamp;
    @NotNull
    double cpuUsage;
    @NotNull
    double memoryUsage;
    @NotNull
    double diskUsage;
    @NotNull
    double networkUpload;
    @NotNull
    double networkDownload;
    @NotNull
    double diskRead;
    @NotNull
    double diskWrite;

    /** v1.3：当前主机所有 GPU 中的最高温度（℃）；无 GPU / 未启用采集时为 null。 */
    Double gpuTemperatureMax;
    /** v1.3：SMART 关键异常计数；无 SMART / 未启用采集时为 null。 */
    Integer smartCriticalCount;
    /** v1.3：失败 systemd 服务数；非 systemd 系统 / 未启用采集时为 null。 */
    Integer systemdFailedCount;
    /** v1.3：关键进程缺失数（未匹配到的 pattern 个数）；未配置 patterns 时为 null。 */
    Integer watchedProcessMissing;
}
