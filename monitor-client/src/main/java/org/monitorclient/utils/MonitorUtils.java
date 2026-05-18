package org.monitorclient.utils;

import lombok.extern.slf4j.Slf4j;
import org.monitorclient.entity.BaseDetail;
import org.monitorclient.entity.RuntimeDetail;
import org.monitorclient.system.OshiSystemInfoProvider;
import org.monitorclient.system.SystemInfoProvider;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.software.os.OperatingSystem;

import java.io.File;
import java.io.IOException;
import java.net.NetworkInterface;
import java.util.Arrays;
import java.util.Objects;
import java.util.Properties;

/**
 * 主机信息采集工具。
 * <p>
 * v1.3 重构：{@link SystemInfo} 改为通过 {@link SystemInfoProvider} 注入，便于单元测试 mock OSHI。
 * 生产构造（无参 / 默认构造）保持向后兼容，内部使用 {@link OshiSystemInfoProvider}。
 */
@Slf4j
public class MonitorUtils {
    private final double GB_TO_BYTES = 1024 * 1024 * 1024.0;
    private final double MB_TO_BYTES = 1024 * 1024.0;
    private final double KB_TO_BYTES = 1024.0;
    private final SystemInfoProvider provider;
    private final Properties properties;

    private long[] previousTicks;
    private long previousUpload;
    private long previousDownload;
    private long previousDiskRead;
    private long previousDiskWrite;
    private long previousTimestamp;

    /**
     * 默认构造：使用 {@link OshiSystemInfoProvider}。
     * 保持与 v1.2 客户端入口的兼容（{@code new MonitorUtils()}）。
     */
    public MonitorUtils() {
        this(new OshiSystemInfoProvider(), System.getProperties());
    }

    /**
     * 显式注入版本：用于单元测试或 Phase 1 子模块自定义 provider。
     *
     * @param provider OSHI 系统信息提供者
     * @param properties JVM 系统属性
     */
    public MonitorUtils(SystemInfoProvider provider, Properties properties) {
        this.provider = provider;
        this.properties = properties;
    }

    /**
     * 采集主机基础静态信息（系统、硬件与网络出口IP）。
     *
     * @return 主机基础信息
     */
    public BaseDetail monitorBaseDetail(){
        SystemInfo info = provider.systemInfo();
        OperatingSystem os = info.getOperatingSystem();
        HardwareAbstractionLayer hardware = info.getHardware();
        double memory = hardware.getMemory().getTotal() / GB_TO_BYTES;
        double diskSize = Arrays.stream(File.listRoots()).mapToLong(File::getTotalSpace).sum() / GB_TO_BYTES;
        String ip = Objects.requireNonNull(this.findNetworkInterface(hardware)).getIPv4addr()[0];
        return new BaseDetail()
                .setOsArch(properties.getProperty("os.arch"))
                .setOsName(os.getFamily())
                .setOsVersion(os.getVersionInfo().getVersion())
                .setOsBit(os.getBitness())
                .setCpuName(hardware.getProcessor().getProcessorIdentifier().getName())
                .setCpuCore(hardware.getProcessor().getLogicalProcessorCount())
                .setMemory(memory)
                .setDisk(diskSize)
                .setIp(ip);
    }

    /**
     * 采集主机实时运行数据，使用跨周期差值计算速率，避免阻塞式 sleep 采样。
     *
     * @return 运行时数据；首次调用仅建立基线并返回 null
     */
    public RuntimeDetail monitorRuntimeDetail() {
        try {
            SystemInfo info = provider.systemInfo();
            HardwareAbstractionLayer hardware = info.getHardware();
            NetworkIF networkInterface = Objects.requireNonNull(this.findNetworkInterface(hardware));
            networkInterface.updateAttributes();
            CentralProcessor processor = hardware.getProcessor();

            long[] currentTicks = processor.getSystemCpuLoadTicks();
            long currentUpload = networkInterface.getBytesSent();
            long currentDownload = networkInterface.getBytesRecv();
            long currentDiskRead = this.sumDiskReadBytes(hardware);
            long currentDiskWrite = this.sumDiskWriteBytes(hardware);
            long currentTimestamp = System.currentTimeMillis();

            if (previousTicks == null) {
                previousTicks = currentTicks;
                previousUpload = currentUpload;
                previousDownload = currentDownload;
                previousDiskRead = currentDiskRead;
                previousDiskWrite = currentDiskWrite;
                previousTimestamp = currentTimestamp;
                return null;
            }

            double elapsedSeconds = Math.max((currentTimestamp - previousTimestamp) / 1000.0, 0.001);
            double upload = (currentUpload - previousUpload) / elapsedSeconds;
            double download = (currentDownload - previousDownload) / elapsedSeconds;
            double read = (currentDiskRead - previousDiskRead) / elapsedSeconds;
            double write = (currentDiskWrite - previousDiskWrite) / elapsedSeconds;
            double cpuUsage = this.calculateCpuUsage(previousTicks, currentTicks);

            previousTicks = currentTicks;
            previousUpload = currentUpload;
            previousDownload = currentDownload;
            previousDiskRead = currentDiskRead;
            previousDiskWrite = currentDiskWrite;
            previousTimestamp = currentTimestamp;

            double memory = (hardware.getMemory().getTotal() - hardware.getMemory().getAvailable()) / GB_TO_BYTES;
            double disk = Arrays.stream(File.listRoots())
                    .mapToLong(file -> file.getTotalSpace() - file.getFreeSpace()).sum() / GB_TO_BYTES;
            return new RuntimeDetail()
                    .setCpuUsage(cpuUsage)
                    .setMemoryUsage(memory)
                    .setDiskUsage(disk)
                    .setNetworkUpload(upload / KB_TO_BYTES)
                    .setNetworkDownload(download / KB_TO_BYTES)
                    .setDiskRead(read / MB_TO_BYTES)
                    .setDiskWrite(write / MB_TO_BYTES)
                    .setTimestamp(currentTimestamp);
        } catch (Exception e) {
            log.error("读取运行时数据出现问题", e);
        }
        return null;
    }

    /**
     * 通过前后两次CPU ticks计算当前周期CPU使用率。
     *
     * @param prevTicks 上一周期ticks
     * @param ticks     当前周期ticks
     * @return CPU使用率（0~1）
     */
    private double calculateCpuUsage(long[] prevTicks, long[] ticks) {
        long nice = ticks[CentralProcessor.TickType.NICE.getIndex()] -
                prevTicks[CentralProcessor.TickType.NICE.getIndex()];
        long irq = ticks[CentralProcessor.TickType.IRQ.getIndex()] -
                prevTicks[CentralProcessor.TickType.IRQ.getIndex()];
        long softIrq = ticks[CentralProcessor.TickType.SOFTIRQ.getIndex()] -
                prevTicks[CentralProcessor.TickType.SOFTIRQ.getIndex()];
        long steal = ticks[CentralProcessor.TickType.STEAL.getIndex()] -
                prevTicks[CentralProcessor.TickType.STEAL.getIndex()];
        long cSys = ticks[CentralProcessor.TickType.SYSTEM.getIndex()] -
                prevTicks[CentralProcessor.TickType.SYSTEM.getIndex()];
        long cUser = ticks[CentralProcessor.TickType.USER.getIndex()] -
                prevTicks[CentralProcessor.TickType.USER.getIndex()];
        long ioWait = ticks[CentralProcessor.TickType.IOWAIT.getIndex()] -
                prevTicks[CentralProcessor.TickType.IOWAIT.getIndex()];
        long idle = ticks[CentralProcessor.TickType.IDLE.getIndex()] -
                prevTicks[CentralProcessor.TickType.IDLE.getIndex()];
        long totalCpu = cUser + nice + cSys + idle + ioWait + irq + softIrq + steal;
        if (totalCpu <= 0) return 0;
        return (cSys + cUser) * 1.0 / totalCpu;
    }

    /**
     * 汇总当前周期磁盘总读字节数。
     *
     * @param hardware 硬件抽象层
     * @return 总读字节
     */
    private long sumDiskReadBytes(HardwareAbstractionLayer hardware) {
        return hardware.getDiskStores().stream().mapToLong(store -> {
            store.updateAttributes();
            return store.getReadBytes();
        }).sum();
    }

    /**
     * 汇总当前周期磁盘总写字节数。
     *
     * @param hardware 硬件抽象层
     * @return 总写字节
     */
    private long sumDiskWriteBytes(HardwareAbstractionLayer hardware) {
        return hardware.getDiskStores().stream().mapToLong(store -> {
            store.updateAttributes();
            return store.getWriteBytes();
        }).sum();
    }

    /**
     * 从网卡列表中挑选可用的业务网卡。
     *
     * @param hardware 硬件抽象层
     * @return 匹配到的网卡，未找到返回 null
     */
    private NetworkIF findNetworkInterface(HardwareAbstractionLayer hardware) {
        try {
            for (NetworkIF network : hardware.getNetworkIFs()) {
                String[] ipv4Addr = network.getIPv4addr();
                NetworkInterface ni = network.queryNetworkInterface();
                if (!ni.isLoopback() && !ni.isPointToPoint() && ni.isUp() && !ni.isVirtual()
                        && (ni.getName().startsWith("eth") || ni.getName().startsWith("en"))
                        && ipv4Addr.length > 0) {
                    return network;
                }
            }
        } catch (IOException e) {
            log.error("读取网络接口信息时出错", e);
        }
        return null;
    }
}
