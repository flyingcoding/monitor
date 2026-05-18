package org.monitorclient.collector;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 单 GPU 数据快照。
 * <p>
 * 由 {@link GpuCollector} 解析 {@code nvidia-smi --query-gpu=index,name,utilization.gpu,
 * memory.used,memory.total,temperature.gpu,power.draw --format=csv,noheader,nounits} 的每行输出生成。
 * 数值字段允许 {@code null}：某些 GPU 不支持 power.draw 查询时 nvidia-smi 会输出 {@code [N/A]}。
 */
@Data
@Accessors(chain = true)
public class GpuStat {
    /** GPU 索引（0-based）。 */
    private Integer index;
    /** GPU 型号名称（如 {@code "NVIDIA GeForce RTX 4090"}）。 */
    private String name;
    /** GPU 利用率百分比（0~100），不可用时为 null。 */
    private Double utilizationPercent;
    /** 已用显存（MB），不可用时为 null。 */
    private Double memoryUsedMb;
    /** 显存总量（MB），不可用时为 null。 */
    private Double memoryTotalMb;
    /** GPU 温度（℃），不可用时为 null。 */
    private Double temperatureCelsius;
    /** GPU 功耗（瓦特），不可用时为 null。 */
    private Double powerDrawWatts;
}
