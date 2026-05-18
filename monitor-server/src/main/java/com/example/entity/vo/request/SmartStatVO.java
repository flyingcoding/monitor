package com.example.entity.vo.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * v1.3：客户端上报的单磁盘 SMART 状态。
 * <p>
 * 由 {@code monitor-client} 的 {@code SmartCollector} 解析 {@code smartctl -A -j} 输出后，
 * 通过 {@code POST /monitor/smart} 接口上报。
 */
@Data
public class SmartStatVO {
    /**
     * 设备路径。
     * <p>
     * 服务端再次校验设备路径白名单（防止恶意客户端注入），与客户端 {@code SmartCollector} 同步。
     */
    @Size(max = 64)
    @Pattern(regexp = "^/dev/(sd[a-z]+|nvme\\d+n\\d+|hd[a-z]+)$",
            message = "设备路径不合法")
    private String device;

    /** 型号名（来自 smartctl model_name 字段），可能为 null。 */
    @Size(max = 128)
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

    /** 温度（℃）；SATA 来自 id=194，NVMe 由 Kelvin 转换。 */
    private Integer temperatureCelsius;

    /** 是否处于关键异常状态。 */
    private boolean critical;
}
