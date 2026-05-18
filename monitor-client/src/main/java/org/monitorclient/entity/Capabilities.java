package org.monitorclient.entity;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 客户端采集能力快照。
 * <p>
 * 由客户端启动时根据 {@code application.properties} 配置开关与系统工具探测结果生成，序列化为
 * JSON 后通过 {@link BaseDetail#capabilitiesJson} 上报。
 * <p>
 * v1.3 Phase 0 仅定义结构骨架；Phase 1 各 Collector 启动时填充自己的字段。
 */
@Data
@Accessors(chain = true)
public class Capabilities {
    /** GPU 采集能力。 */
    private Module gpu;
    /** SMART 采集能力。 */
    private Module smart;
    /** systemd 采集能力。 */
    private Module systemd;
    /** 进程关键字采集能力。 */
    private Module process;

    /**
     * 单个采集模块的能力描述。
     */
    @Data
    @Accessors(chain = true)
    public static class Module {
        /** 是否在 application.properties 启用。 */
        private boolean enabled;
        /** 系统工具/能力是否可用（如 nvidia-smi 是否存在）。 */
        private boolean available;
        /** 模块特定的元数据（如 GPU 设备数 / SMART devices 列表 / systemd units 列表 / process patterns）。 */
        private List<String> items;
        /** 设备数量（GPU / SMART 设备数）。 */
        private Integer count;
        /** 模块禁用原因（如 "nvidia-smi 未检测到"），用于 admin UI 提示。 */
        private String unavailableReason;
    }
}
