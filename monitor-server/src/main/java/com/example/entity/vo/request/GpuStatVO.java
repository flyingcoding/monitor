package com.example.entity.vo.request;

import lombok.Data;

/**
 * 单 GPU 设备上报字段。
 * <p>
 * 与客户端 {@code org.monitorclient.collector.GpuStat} 结构一致；
 * 所有数值字段允许 {@code null}（个别 GPU 不支持 power.draw 等查询时 nvidia-smi 输出 {@code [N/A]}）。
 */
@Data
public class GpuStatVO {
    /** GPU 索引（0-based）。 */
    private Integer index;
    /** GPU 型号名称。 */
    private String name;
    /** GPU 利用率百分比（0~100）。 */
    private Double utilizationPercent;
    /** 已用显存（MB）。 */
    private Double memoryUsedMb;
    /** 显存总量（MB）。 */
    private Double memoryTotalMb;
    /** GPU 温度（℃）。 */
    private Double temperatureCelsius;
    /** GPU 功耗（瓦特）。 */
    private Double powerDrawWatts;
}
