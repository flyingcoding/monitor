package org.monitorclient.collector;

import lombok.extern.slf4j.Slf4j;
import org.monitorclient.entity.Capabilities;
import org.monitorclient.entity.RuntimeDetail;
import org.monitorclient.system.MetricCollector;
import org.monitorclient.system.SystemInfoProvider;
import org.monitorclient.utils.NetUtils;
import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * 进程监控采集器。
 * <p>
 * 每 10s 一次：
 * <ol>
 *   <li>调用 OSHI {@code OperatingSystem.getProcesses()} 获取全部进程；</li>
 *   <li>按 {@code monitor.collect.process.patterns} 正则匹配 name / commandLine，统计每条 pattern
 *       是否匹配到至少一个进程，把"缺失数"写入 {@link RuntimeDetail#setWatchedProcessMissing};</li>
 *   <li>按 CPU / RSS 内存倒序各取 Top N，构成 {@link ProcessSnapshot}；</li>
 *   <li>通过 {@link NetUtils#postProcessSnapshot} 与 RuntimeDetail 分离上报到 {@code /monitor/process}。</li>
 * </ol>
 * 设计约束（v1.3 D8）：所有 OSHI 调用走 {@link SystemInfoProvider} 注入，便于单元测试 mock。
 */
@Slf4j
public class ProcessCollector implements MetricCollector {

    /** 配置 key：进程匹配 pattern 列表，逗号分隔。 */
    public static final String KEY_PATTERNS = "monitor.collect.process.patterns";
    /** 配置 key：Top N 个数；默认 10，最大 50。 */
    public static final String KEY_TOP_N = "monitor.collect.process.topN";

    /** 默认 Top N。 */
    static final int DEFAULT_TOP_N = 10;
    /** 最大 Top N。 */
    static final int MAX_TOP_N = 50;

    private final SystemInfoProvider provider;
    private final NetUtils net;
    private final List<Pattern> patterns;
    private final List<String> rawPatterns;
    private final int topN;
    private final boolean enabled;
    private final AtomicReference<ProcessSnapshot> lastSnapshot = new AtomicReference<>();

    /**
     * 构造进程采集器。
     *
     * @param provider OSHI 注入接口
     * @param net 网络上报工具
     * @param config 客户端配置（读取 monitor.collect.process.* 项）
     */
    public ProcessCollector(SystemInfoProvider provider, NetUtils net, Properties config) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.net = net;
        Properties props = config == null ? new Properties() : config;
        this.rawPatterns = parseRawPatterns(props.getProperty(KEY_PATTERNS));
        this.patterns = compilePatterns(this.rawPatterns);
        this.topN = parseTopN(props.getProperty(KEY_TOP_N));
        // 启用条件：patterns 非空（即使 Top N 总是采集，没有 watched 时该模块视为未启用以保持 D2 语义）。
        this.enabled = !this.rawPatterns.isEmpty();
    }

    @Override
    public String name() {
        return "process";
    }

    @Override
    public Capabilities.Module describe() {
        Capabilities.Module module = new Capabilities.Module()
                .setEnabled(enabled)
                .setAvailable(true)
                .setItems(rawPatterns);
        if (!enabled) {
            module.setUnavailableReason("monitor.collect.process.patterns 未配置");
        }
        return module;
    }

    @Override
    public void enhance(RuntimeDetail runtime) {
        if (runtime == null) {
            return;
        }
        if (!enabled) {
            return;
        }
        try {
            List<OSProcess> processes = listProcesses();
            ProcessSnapshot snapshot = buildSnapshot(processes);
            int missing = countMissingPatterns(snapshot.getWatchedPatterns());
            runtime.setWatchedProcessMissing(missing);
            lastSnapshot.set(snapshot);
            postSnapshotSafely(snapshot);
        } catch (Exception e) {
            log.warn("ProcessCollector 执行异常：{}", e.getClass().getSimpleName());
        }
    }

    /**
     * 返回内存中最近一次的进程快照，供本进程内查询（如调试 HTTP / JMX）。
     *
     * @return 最近一次快照；尚未执行 enhance 时返回 null
     */
    public ProcessSnapshot lastSnapshot() {
        return lastSnapshot.get();
    }

    /**
     * 上报快照，吞掉异常避免影响主采集流程。
     *
     * @param snapshot 待上报快照
     */
    private void postSnapshotSafely(ProcessSnapshot snapshot) {
        if (net == null) {
            return;
        }
        try {
            net.postProcessSnapshot(snapshot);
        } catch (Exception e) {
            log.warn("上报进程快照失败：{}", e.getClass().getSimpleName());
        }
    }

    /**
     * 拉取当前进程列表，OSHI 不可用时返回空。
     *
     * @return 进程列表
     */
    private List<OSProcess> listProcesses() {
        SystemInfo info = provider.systemInfo();
        if (info == null) {
            return java.util.Collections.emptyList();
        }
        OperatingSystem os = info.getOperatingSystem();
        if (os == null) {
            return java.util.Collections.emptyList();
        }
        List<OSProcess> raw = os.getProcesses();
        return raw == null ? java.util.Collections.emptyList() : raw;
    }

    /**
     * 构造一份新的进程快照（含 Top N 与 watched pattern 状态）。
     *
     * @param processes 进程列表
     * @return 快照
     */
    ProcessSnapshot buildSnapshot(List<OSProcess> processes) {
        ProcessSnapshot snapshot = new ProcessSnapshot()
                .setTimestamp(System.currentTimeMillis())
                .setTop10ByCpu(topN(processes, ProcessCollector::cpuOf))
                .setTop10ByMemory(topNByMemory(processes))
                .setWatchedPatterns(matchPatterns(processes));
        return snapshot;
    }

    /**
     * 取 CPU 使用率倒序的 Top N（CPU 字段缺失视为 0）。
     *
     * @param processes 进程列表
     * @param scorer CPU 评分函数
     * @return Top N
     */
    private List<ProcessSnapshot.ProcessInfo> topN(List<OSProcess> processes,
                                                   java.util.function.ToDoubleFunction<OSProcess> scorer) {
        Comparator<OSProcess> byCpu = Comparator.comparingDouble(scorer).reversed();
        return selectTop(processes, byCpu);
    }

    /**
     * 取 RSS 物理内存倒序的 Top N。
     *
     * @param processes 进程列表
     * @return Top N
     */
    private List<ProcessSnapshot.ProcessInfo> topNByMemory(List<OSProcess> processes) {
        Comparator<OSProcess> byMem = Comparator.comparingLong(OSProcess::getResidentSetSize).reversed();
        return selectTop(processes, byMem);
    }

    /** Keeps O(topN) sorting state instead of sorting a second copy of all processes. */
    private List<ProcessSnapshot.ProcessInfo> selectTop(List<OSProcess> processes, Comparator<OSProcess> bestFirst) {
        PriorityQueue<OSProcess> heap = new PriorityQueue<>(topN, bestFirst.reversed());
        for (OSProcess process : processes) {
            if (process == null) continue;
            if (heap.size() < topN) heap.offer(process);
            else if (bestFirst.compare(process, heap.peek()) < 0) {
                heap.poll();
                heap.offer(process);
            }
        }
        return heap.stream().sorted(bestFirst).map(this::toInfo).collect(Collectors.toList());
    }

    /**
     * 计算每个 pattern 是否匹配到至少一个进程。
     *
     * @param processes 进程列表
     * @return pattern 字符串 → 匹配到至少一个进程
     */
    Map<String, Boolean> matchPatterns(List<OSProcess> processes) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (int i = 0; i < patterns.size(); i++) {
            Pattern pattern = patterns.get(i);
            String raw = rawPatterns.get(i);
            boolean hit = false;
            for (OSProcess p : processes) {
                if (p == null) continue;
                String name = bounded(p.getName());
                String cmd = bounded(p.getCommandLine());
                if (pattern.matcher(name).find() || pattern.matcher(cmd).find()) {
                    hit = true;
                    break;
                }
            }
            result.put(raw, hit);
        }
        return result;
    }

    /**
     * 统计未匹配到的 pattern 数。
     *
     * @param watched 已计算的 watched pattern 状态
     * @return 未匹配 pattern 数
     */
    private int countMissingPatterns(Map<String, Boolean> watched) {
        if (watched == null) return 0;
        int count = 0;
        for (Boolean matched : watched.values()) {
            if (matched == null || !matched) count++;
        }
        return count;
    }

    /**
     * 把 OSHI 的 OSProcess 转换为上报用的 ProcessInfo（取 OSHI 时间已采集到的 cpu 字段）。
     *
     * @param p 进程
     * @return 进程信息
     */
    private ProcessSnapshot.ProcessInfo toInfo(OSProcess p) {
        return new ProcessSnapshot.ProcessInfo()
                .setName(bounded(p.getName()))
                .setPid(p.getProcessID())
                .setCpuPercent(cpuOf(p))
                .setMemoryBytes(p.getResidentSetSize());
    }

    /**
     * 取 OSHI 进程的累积 CPU 使用率，缺失或异常归零。
     *
     * @param p 进程
     * @return CPU 使用率（0~1）
     */
    private static double cpuOf(OSProcess p) {
        try {
            double value = p.getProcessCpuLoadCumulative();
            if (Double.isNaN(value) || Double.isInfinite(value) || value < 0) {
                return 0.0;
            }
            return value;
        } catch (Exception e) {
            return 0.0;
        }
    }

    /**
     * 解析 patterns 配置为原始字符串列表（保持顺序、去掉空白条目）。
     *
     * @param raw 配置原始值
     * @return 原始 pattern 字符串列表
     */
    private static List<String> parseRawPatterns(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return java.util.Collections.emptyList();
        }
        List<String> result = Arrays.stream(raw.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        if (result.size() > 32 || result.stream().anyMatch(pattern -> pattern.length() > 256)) {
            throw new IllegalArgumentException("Process patterns are limited to 32 entries of 256 characters");
        }
        return result;
    }

    /**
     * 编译每个 pattern 字符串为 {@link Pattern}；非法正则跳过并打 warn 日志。
     *
     * @param raws 原始 pattern 字符串列表
     * @return 已编译 Pattern 列表（仍以原顺序），与 raws 等长，跳过的项填占位
     */
    private static List<Pattern> compilePatterns(List<String> raws) {
        List<Pattern> compiled = new ArrayList<>();
        for (String raw : raws) {
            try {
                compiled.add(Pattern.compile(raw));
            } catch (PatternSyntaxException e) {
                log.warn("非法 process pattern 已跳过：{} ({})", raw, e.getDescription());
                // 用永远不命中的占位 pattern 占位，保证 rawPatterns / patterns 长度对齐
                compiled.add(Pattern.compile("(?!x)x"));
            }
        }
        return compiled;
    }

    /**
     * 解析 topN 配置，越界回退默认值。
     *
     * @param raw 配置原始值
     * @return 1..MAX_TOP_N 之间的整数
     */
    private static int parseTopN(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_TOP_N;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            if (v < 1) return DEFAULT_TOP_N;
            return Math.min(v, MAX_TOP_N);
        } catch (NumberFormatException e) {
            log.warn("非法 topN 配置 {} 回退默认 {}", raw, DEFAULT_TOP_N);
            return DEFAULT_TOP_N;
        }
    }

    /** Bounds native command-line strings before matching or snapshot serialization. */
    private static String bounded(String value) {
        return value == null ? "" : value.substring(0, Math.min(4096, value.length()));
    }

    // 仅测试用：把 patterns 字符串视图暴露出来便于断言
    List<String> rawPatternsForTest() {
        return new ArrayList<>(rawPatterns);
    }

    int topNForTest() {
        return topN;
    }
}
