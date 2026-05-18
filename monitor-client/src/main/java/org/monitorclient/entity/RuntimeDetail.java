package org.monitorclient.entity;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 客户端运行时数据。
 * <p>
 * 基础 8 字段（{@code cpuUsage} ~ {@code timestamp}）由所有客户端必填。
 * v1.3 新增 4 个可选聚合字段：GPU 最高温度 / SMART 关键异常数 / 失败 systemd 服务数 / 关键进程缺失数；
 * 当 application.properties 未启用对应采集 / 系统工具缺失时为 {@code null}，
 * 服务端 AlertEvaluator 跳过对应规则评估，不会误告警。
 */
@Data
@Accessors(chain = true)
public class RuntimeDetail {
    long timestamp;
    double cpuUsage;
    double memoryUsage;
    double diskUsage;
    double networkUpload;
    double networkDownload;
    double diskRead;
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
