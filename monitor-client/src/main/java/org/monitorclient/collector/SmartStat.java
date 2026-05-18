package org.monitorclient.collector;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 单磁盘 SMART 快照。
 * <p>
 * 由 {@link SmartCollector} 解析 {@code smartctl -A -j} 输出后填充：
 * <ul>
 *   <li>SATA：从 {@code ata_smart_attributes.table[]} 提取 id=5 / 197 / 198 / 194；</li>
 *   <li>NVMe：从 {@code nvme_smart_health_information_log} 提取 critical_warning / media_errors / temperature。</li>
 * </ul>
 * {@link #critical} 判定标准与 v1.3 prd R26 一致：reallocated/pending/uncorrectable/
 * critical_warning/media_errors 任意 &gt; 0 即视为该设备 critical。
 */
@Data
@Accessors(chain = true)
public class SmartStat {
    /** 设备路径（如 {@code /dev/sda} / {@code /dev/nvme0n1}）。 */
    private String device;
    /** 型号名（来自 {@code model_name} 字段），可能为 null。 */
    private String modelName;
    /** 是否为 NVMe 设备。 */
    private boolean nvme;
    /** SATA：id=5 raw 值；NVMe：null。 */
    private Long reallocatedSector;
    /** SATA：id=197 raw 值；NVMe：null。 */
    private Long currentPending;
    /** SATA：id=198 raw 值；NVMe：null。 */
    private Long offlineUncorrectable;
    /** NVMe：media_errors 计数；SATA：null。 */
    private Long mediaErrors;
    /** 温度（℃）；SATA 来自 id=194，NVMe 由 Kelvin -273 转换。 */
    private Integer temperatureCelsius;
    /** 是否处于关键异常状态。 */
    private boolean critical;
}
