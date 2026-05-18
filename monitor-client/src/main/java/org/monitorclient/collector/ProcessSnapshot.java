package org.monitorclient.collector;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Map;

/**
 * 进程快照。
 * <p>
 * 由 {@link ProcessCollector} 在每个 10s 采集周期生成，包含：
 * <ul>
 *   <li>{@code top10ByCpu} —— CPU 使用率排序的 Top N 进程；</li>
 *   <li>{@code top10ByMemory} —— 物理内存（RSS）排序的 Top N 进程；</li>
 *   <li>{@code watchedPatterns} —— 配置的关键进程正则，值为是否匹配到至少一个运行进程。</li>
 * </ul>
 * 由 {@code NetUtils#postProcessSnapshot} 与 RuntimeDetail 分离上报到 {@code /monitor/process}。
 */
@Data
@Accessors(chain = true)
public class ProcessSnapshot {

    /** 快照时间戳（毫秒）。 */
    long timestamp;

    /** Top N（默认 10）按 CPU 使用率倒序排列的进程列表。 */
    List<ProcessInfo> top10ByCpu;

    /** Top N（默认 10）按 RSS 物理内存倒序排列的进程列表。 */
    List<ProcessInfo> top10ByMemory;

    /** 配置的关键进程 pattern → 是否匹配到至少一个运行进程。 */
    Map<String, Boolean> watchedPatterns;

    /**
     * 单条进程信息。
     */
    @Data
    @Accessors(chain = true)
    public static class ProcessInfo {
        /** 进程名（OSHI {@code OSProcess.getName()}）。 */
        String name;
        /** 进程 ID。 */
        int pid;
        /** CPU 使用率（0~1）。 */
        double cpuPercent;
        /** 物理内存（RSS）字节数。 */
        long memoryBytes;
    }
}
