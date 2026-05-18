package org.monitorclient.system;

import oshi.SystemInfo;

/**
 * OSHI 系统信息抽象层。
 * <p>
 * 把 {@link SystemInfo} 等直接构造抽象为可注入接口，便于 v1.3 各 Collector
 * （ProcessCollector / GpuCollector / SmartCollector / SystemdCollector）的单元测试。
 * <p>
 * 生产代码使用 {@link OshiSystemInfoProvider} 默认实现；测试代码注入 mock 返回固定数据。
 */
public interface SystemInfoProvider {
    /**
     * 返回 OSHI 全局入口。同一进程内通常单例。
     *
     * @return SystemInfo 实例
     */
    SystemInfo systemInfo();
}
