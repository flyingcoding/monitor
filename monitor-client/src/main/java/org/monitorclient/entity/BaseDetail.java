package org.monitorclient.entity;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 客户端基础静态信息。
 * <p>
 * v1.3 新增 {@code capabilitiesJson} 字段：客户端启动时根据 application.properties 配置 +
 * 系统工具探测结果生成的能力 JSON，随 {@code /monitor/detail} 上报，admin 在 Manage 页面可见。
 */
@Data
@Accessors(chain = true)
public class BaseDetail {
    String osArch;
    String osName;
    String osVersion;
    int osBit;
    String cpuName;
    int cpuCore;
    double memory;
    double disk;
    String ip;
    /** v1.3：客户端采集能力 JSON；无 v1.3 采集模块启用时为 null。 */
    String capabilitiesJson;
}
