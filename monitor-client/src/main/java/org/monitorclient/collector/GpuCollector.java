package org.monitorclient.collector;

import lombok.extern.slf4j.Slf4j;
import org.monitorclient.entity.Capabilities;
import org.monitorclient.entity.RuntimeDetail;
import org.monitorclient.system.CommandExecutor;
import org.monitorclient.system.MetricCollector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/**
 * NVIDIA GPU 指标采集器。
 * <p>
 * 通过执行 {@code nvidia-smi --query-gpu=...} 采集每张 NVIDIA GPU 的利用率 / 显存 / 温度 / 功耗，
 * 聚合最高温度写入 {@link RuntimeDetail#setGpuTemperatureMax(Double)} 用于告警评估，
 * 同时把完整列表保存在 {@link #lastSnapshot} 供 {@code MonitorClientApplication} 独立上报到
 * {@code /monitor/gpu} 端点（与 RuntimeDetail 分离，避免污染基础时序数据）。
 * <p>
 * 工作约束：
 * <ul>
 *   <li>仅在 {@code monitor.collect.gpu.enabled=true} 时启用（默认 false，保护无 GPU 主机不浪费 CPU）。</li>
 *   <li>构造时调用 {@code CommandExecutor.isAvailable("nvidia-smi")} 探测；不可用时 enabled+available=false。</li>
 *   <li>单次 {@code nvidia-smi} 执行预算 ≤ 3 秒；超时由 {@link CommandExecutor} 处理。</li>
 *   <li>解析失败 / 命令失败 / 超时 → rt 字段保持 null，{@link #lastSnapshot} 重置为空。</li>
 *   <li>AMD / Intel GPU 不在 v1.3 支持范围（Out of Scope）。</li>
 * </ul>
 */
@Slf4j
public class GpuCollector implements MetricCollector {

    /** application.properties 启用开关。 */
    static final String CONFIG_KEY_ENABLED = "monitor.collect.gpu.enabled";
    /** 单次 nvidia-smi 执行超时上限。 */
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(3);
    /** nvidia-smi 主命令。 */
    private static final String NVIDIA_SMI = "nvidia-smi";
    /** GPU 查询字段，与 {@link GpuStat} 字段顺序一致。 */
    private static final List<String> QUERY_GPU_COMMAND = List.of(
            NVIDIA_SMI,
            "--query-gpu=index,name,utilization.gpu,memory.used,memory.total,temperature.gpu,power.draw",
            "--format=csv,noheader,nounits");

    private final CommandExecutor executor;
    private final boolean enabled;
    private final boolean available;
    private final Integer deviceCount;
    private final String unavailableReason;

    /** 最近一次采集到的 GPU 列表；线程安全发布给独立上报线程。 */
    private final AtomicReference<List<GpuStat>> lastSnapshot = new AtomicReference<>(Collections.emptyList());

    /**
     * 构造 GPU 采集器，启动期完成能力探测。
     *
     * @param executor 命令执行抽象（生产用 {@code ProcessCommandExecutor}，测试注入 mock）
     * @param config application.properties 解析后的配置（读 {@code monitor.collect.gpu.enabled}）
     */
    public GpuCollector(CommandExecutor executor, Properties config) {
        this.executor = executor;
        this.enabled = Boolean.parseBoolean(config.getProperty(CONFIG_KEY_ENABLED, "false"));
        if (!enabled) {
            this.available = false;
            this.deviceCount = null;
            this.unavailableReason = null;
            return;
        }
        boolean toolPresent = executor.isAvailable(NVIDIA_SMI);
        if (!toolPresent) {
            this.available = false;
            this.deviceCount = null;
            this.unavailableReason = "nvidia-smi 未检测到";
            log.info("monitor.collect.gpu.enabled=true 但 nvidia-smi 未检测到，已禁用 GPU 采集");
            return;
        }
        this.available = true;
        this.deviceCount = probeDeviceCount();
        this.unavailableReason = null;
        log.info("GPU 采集已启用，检测到 {} 张 NVIDIA GPU", this.deviceCount == null ? "未知数量" : this.deviceCount);
    }

    /**
     * 返回模块名（用于 Capabilities JSON 的 key）。
     *
     * @return 固定字符串 {@code "gpu"}
     */
    @Override
    public String name() {
        return "gpu";
    }

    /**
     * 描述本采集器的能力快照。
     *
     * @return Capabilities.Module，含 enabled / available / count / unavailableReason
     */
    @Override
    public Capabilities.Module describe() {
        Capabilities.Module module = new Capabilities.Module()
                .setEnabled(enabled)
                .setAvailable(available);
        if (deviceCount != null) {
            module.setCount(deviceCount);
        }
        if (unavailableReason != null) {
            module.setUnavailableReason(unavailableReason);
        }
        return module;
    }

    /**
     * 执行 nvidia-smi 采集 + 聚合最高温度。
     * <p>
     * 失败时保持 {@code rt.gpuTemperatureMax = null} 且 {@link #lastSnapshot} 置空，
     * 由调用方上报 null 给服务端，{@code AlertEvaluator} 自动跳过对应规则。
     *
     * @param runtime 本周期 RuntimeDetail
     */
    @Override
    public void enhance(RuntimeDetail runtime) {
        if (!enabled || !available) {
            return;
        }
        List<GpuStat> stats = collect();
        if (stats.isEmpty()) {
            lastSnapshot.set(Collections.emptyList());
            return;
        }
        lastSnapshot.set(Collections.unmodifiableList(stats));
        Double maxTemp = stats.stream()
                .map(GpuStat::getTemperatureCelsius)
                .filter(java.util.Objects::nonNull)
                .max(Double::compareTo)
                .orElse(null);
        if (maxTemp != null) {
            runtime.setGpuTemperatureMax(maxTemp);
        }
    }

    /**
     * 返回最近一次采集的 GPU 列表快照（不可变副本）。供 {@code NetUtils.postGpuSnapshot} 独立上报。
     *
     * @return 不可变列表，无数据时返回空列表
     */
    public List<GpuStat> lastSnapshot() {
        return lastSnapshot.get();
    }

    /**
     * 启动期执行一次 {@code nvidia-smi -L} 解析设备数量。
     * <p>
     * 输出形如：{@code GPU 0: NVIDIA GeForce RTX 4090 (UUID: GPU-xxxx)}。
     * 执行失败或解析失败返回 null（不影响 enabled / available 判定）。
     *
     * @return 设备数量，失败时 null
     */
    private Integer probeDeviceCount() {
        try {
            CommandExecutor.CommandResult result = executor.execute(
                    List.of(NVIDIA_SMI, "-L"), COMMAND_TIMEOUT);
            if (!result.success()) {
                log.debug("nvidia-smi -L 失败 exitCode={} stderr={}", result.exitCode(), result.stderr());
                return null;
            }
            String stdout = result.stdout();
            if (stdout == null || stdout.isBlank()) {
                return 0;
            }
            int count = 0;
            for (String line : stdout.split("\n")) {
                if (line.trim().startsWith("GPU ")) {
                    count++;
                }
            }
            return count;
        } catch (Exception e) {
            log.debug("nvidia-smi -L 探测异常：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 执行 nvidia-smi 查询并解析所有 GPU 行。
     * <p>
     * 命令失败 / 超时 / 输出空时返回空列表；个别行解析失败时跳过该行，其余正常返回。
     *
     * @return GPU 数据列表，无数据时为空列表
     */
    private List<GpuStat> collect() {
        CommandExecutor.CommandResult result;
        try {
            result = executor.execute(QUERY_GPU_COMMAND, COMMAND_TIMEOUT);
        } catch (Exception e) {
            log.warn("nvidia-smi 调用异常：{}", e.getMessage());
            return Collections.emptyList();
        }
        if (result.timedOut()) {
            log.warn("nvidia-smi 调用超时（{}）", COMMAND_TIMEOUT);
            return Collections.emptyList();
        }
        if (!result.success()) {
            log.warn("nvidia-smi 调用失败 exitCode={} stderr={}", result.exitCode(),
                    result.stderr() == null ? "" : result.stderr().trim());
            return Collections.emptyList();
        }
        String stdout = result.stdout();
        if (stdout == null || stdout.isBlank()) {
            return Collections.emptyList();
        }
        List<GpuStat> stats = new ArrayList<>();
        for (String line : stdout.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            GpuStat stat = parseLine(trimmed);
            if (stat != null) {
                stats.add(stat);
            }
        }
        return stats;
    }

    /**
     * 解析一行 nvidia-smi CSV 输出为 GpuStat。
     * <p>
     * 期望字段顺序：{@code index,name,utilization.gpu,memory.used,memory.total,temperature.gpu,power.draw}。
     * 字段数量少于 7 视为格式错误，整行跳过。个别字段为 {@code [N/A]} / 无法解析为数字时填 null。
     *
     * @param line CSV 行（已 trim）
     * @return 解析后的 GpuStat；格式错误时 null
     */
    private GpuStat parseLine(String line) {
        String[] parts = line.split(",");
        if (parts.length < 7) {
            log.warn("nvidia-smi 输出格式异常（字段不足 7 个），已跳过：{}", line);
            return null;
        }
        try {
            Integer index = parseInteger(parts[0]);
            if (index == null) {
                log.warn("nvidia-smi 输出 index 解析失败，已跳过：{}", line);
                return null;
            }
            return new GpuStat()
                    .setIndex(index)
                    .setName(parts[1].trim())
                    .setUtilizationPercent(parseDouble(parts[2]))
                    .setMemoryUsedMb(parseDouble(parts[3]))
                    .setMemoryTotalMb(parseDouble(parts[4]))
                    .setTemperatureCelsius(parseDouble(parts[5]))
                    .setPowerDrawWatts(parseDouble(parts[6]));
        } catch (Exception e) {
            log.warn("nvidia-smi 行解析异常 line={}, reason={}", line, e.getMessage());
            return null;
        }
    }

    /**
     * 容错解析整数字段。{@code [N/A]} / 空 / 非数字均返回 null。
     *
     * @param raw 原始字段
     * @return 解析结果或 null
     */
    private Integer parseInteger(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || "[N/A]".equalsIgnoreCase(trimmed) || "N/A".equalsIgnoreCase(trimmed)) {
            return null;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 容错解析浮点字段。{@code [N/A]} / 空 / 非数字均返回 null。
     *
     * @param raw 原始字段
     * @return 解析结果或 null
     */
    private Double parseDouble(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || "[N/A]".equalsIgnoreCase(trimmed) || "N/A".equalsIgnoreCase(trimmed)) {
            return null;
        }
        try {
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
