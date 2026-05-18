package com.example.entity.dto;

import com.influxdb.annotations.Column;
import com.influxdb.annotations.Measurement;
import lombok.Data;

import java.time.Instant;

/**
 * InfluxDB {@code runtime} measurement 实体。
 * <p>
 * 基础 7 字段记录每个客户端的实时系统指标（与 v1.0 保持兼容）。
 * v1.3 新增 4 个可选聚合字段（{@code gpuTemperatureMax} / {@code smartCriticalCount} /
 * {@code systemdFailedCount} / {@code watchedProcessMissing}）；当客户端未启用对应采集时
 * 字段为 {@code null}，{@code WriteApi} 不会将 null 字段写入 InfluxDB（自动跳过）。
 */
@Data
@Measurement(name = "runtime")
public class RuntimeData {
    @Column(tag = true)
    int clientId;
    @Column(timestamp = true)
    Instant timestamp;
    @Column
    double cpuUsage;
    @Column
    double memoryUsage;
    @Column
    double diskUsage;
    @Column
    double networkUpload;
    @Column
    double networkDownload;
    @Column
    double diskRead;
    @Column
    double diskWrite;

    /** v1.3：当前主机所有 GPU 中的最高温度（℃）；无对应采集时为 null，跳过写入。 */
    @Column
    Double gpuTemperatureMax;
    /** v1.3：SMART 关键异常计数；无对应采集时为 null。 */
    @Column
    Integer smartCriticalCount;
    /** v1.3：失败 systemd 服务数；无对应采集时为 null。 */
    @Column
    Integer systemdFailedCount;
    /** v1.3：关键进程缺失数；无对应采集时为 null。 */
    @Column
    Integer watchedProcessMissing;
}
