package org.monitorclient.system;

import oshi.SystemInfo;

/**
 * 默认 OSHI 实现：单例持有 {@link SystemInfo}。
 */
public class OshiSystemInfoProvider implements SystemInfoProvider {

    private final SystemInfo systemInfo = new SystemInfo();

    @Override
    public SystemInfo systemInfo() {
        return systemInfo;
    }
}
