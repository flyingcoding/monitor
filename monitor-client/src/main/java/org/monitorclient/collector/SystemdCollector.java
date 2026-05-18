package org.monitorclient.collector;

import lombok.extern.slf4j.Slf4j;
import org.monitorclient.entity.Capabilities;
import org.monitorclient.entity.RuntimeDetail;
import org.monitorclient.system.CommandExecutor;
import org.monitorclient.system.MetricCollector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * v1.3 systemd 服务状态采集器。
 * <p>
 * 通过 {@code systemctl show <unit> --property=LoadState,ActiveState,SubState,Description}
 * 解析每个 unit 的状态，聚合 {@code !healthy} 的数量上报至 {@code RuntimeDetail.systemdFailedCount}。
 * <p>
 * 启动时探测 {@code systemctl --version}，非 systemd 系统（macOS / Windows）整个模块禁用，
 * {@link #describe()} 返回 {@code available=false}，{@link #enhance(RuntimeDetail)} 早退保持字段 null。
 * <p>
 * 详情快照（List&lt;SystemdUnitStat&gt;）保存于 {@link #lastSnapshot}，供独立的 SystemdSnapshot
 * 上报通道读取（线程安全，{@code AtomicReference}）。
 */
@Slf4j
public class SystemdCollector implements MetricCollector {

    /** systemctl show 调用单 unit 的执行预算（PRD 约束 ≤ 2s）。 */
    private static final Duration SYSTEMCTL_TIMEOUT = Duration.ofSeconds(2);

    /**
     * Unit 名校验：只允许字母数字、点、连字符、下划线、@、冒号（含实例名如 sshd@root.service）。
     * 拒绝 {@code &|;<>$`"'\} 等 shell 元字符，防止命令注入。
     */
    private static final Pattern UNIT_NAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._@:\\-]*$");

    private final CommandExecutor executor;
    /** 配置启用的 watched units 列表（已通过正则白名单过滤）。 */
    private final List<String> units;
    /** systemctl 是否可用（启动时探测）。 */
    private final boolean systemctlAvailable;
    /** application.properties 是否启用本模块（units 列表非空）。 */
    private final boolean enabled;
    /** 当 {@code available=false} 时的禁用原因（用于 admin UI 显示）。 */
    private final String unavailableReason;

    /** 本周期采集到的 unit 状态快照，供详情快照上报通道读取。 */
    private final AtomicReference<List<SystemdUnitStat>> lastSnapshot =
            new AtomicReference<>(Collections.emptyList());

    /**
     * 构造采集器。
     *
     * @param executor 命令执行器（生产用 ProcessCommandExecutor，测试用 mock）
     * @param config application.properties 配置（读 {@code monitor.collect.systemd.units}）
     */
    public SystemdCollector(CommandExecutor executor, Properties config) {
        this.executor = executor;
        this.units = parseUnits(config);
        this.enabled = !this.units.isEmpty();
        if (!this.enabled) {
            this.systemctlAvailable = false;
            this.unavailableReason = "未配置 monitor.collect.systemd.units";
            return;
        }
        boolean available = false;
        String reason = null;
        try {
            available = executor.isAvailable("systemctl");
            if (!available) {
                reason = "systemctl 未检测到，非 systemd 系统将自动禁用";
            }
        } catch (Exception e) {
            reason = "systemctl 探测异常：" + e.getMessage();
        }
        this.systemctlAvailable = available;
        this.unavailableReason = reason;
        if (available) {
            log.info("systemd 采集器启用，watched units = {}", this.units);
        } else {
            log.info("systemd 采集器跳过：{}", reason);
        }
    }

    @Override
    public String name() {
        return "systemd";
    }

    @Override
    public Capabilities.Module describe() {
        Capabilities.Module module = new Capabilities.Module();
        module.setEnabled(enabled);
        module.setAvailable(enabled && systemctlAvailable);
        module.setItems(new ArrayList<>(units));
        module.setCount(units.size());
        if (unavailableReason != null) {
            module.setUnavailableReason(unavailableReason);
        }
        return module;
    }

    @Override
    public void enhance(RuntimeDetail runtime) {
        if (!enabled || !systemctlAvailable) {
            // 未启用或系统不可用：保持字段 null，AlertEvaluator 自动跳过
            lastSnapshot.set(Collections.emptyList());
            return;
        }
        List<SystemdUnitStat> snapshot = new ArrayList<>(units.size());
        int failedCount = 0;
        for (String unit : units) {
            SystemdUnitStat stat = querySingleUnit(unit);
            if (stat == null) {
                // 单 unit 解析失败：不计入 snapshot，不影响其他 unit
                continue;
            }
            snapshot.add(stat);
            if (!stat.isHealthy()) {
                failedCount++;
            }
        }
        lastSnapshot.set(snapshot);
        runtime.setSystemdFailedCount(failedCount);
    }

    /**
     * 返回本周期的 unit 快照副本（线程安全）。
     *
     * @return 不可变副本
     */
    public List<SystemdUnitStat> snapshot() {
        return new ArrayList<>(lastSnapshot.get());
    }

    /**
     * 执行 systemctl show 并解析输出，单 unit 异常吞掉。
     *
     * @param unit unit 名（已通过白名单校验）
     * @return 解析后的 stat；失败返回 null
     */
    private SystemdUnitStat querySingleUnit(String unit) {
        try {
            CommandExecutor.CommandResult result = executor.execute(
                    List.of("systemctl", "show", unit,
                            "--property=LoadState,ActiveState,SubState,Description",
                            "--no-pager"),
                    SYSTEMCTL_TIMEOUT);
            // 注意：systemctl show 对不存在 unit 仍返回 exit 0 + LoadState=not-found，
            // 这里不严格要求 success（避免漏掉 LoadState=not-found 的诊断信息）
            if (result.timedOut()) {
                log.warn("systemctl show {} 超时，跳过", unit);
                return null;
            }
            return parseShowOutput(unit, result.stdout());
        } catch (Exception e) {
            log.warn("systemctl show {} 解析失败：{}", unit, e.getMessage());
            return null;
        }
    }

    /**
     * 解析 {@code Key=Value} 多行输出。
     * <p>
     * 一个示例输出：
     * <pre>
     * LoadState=loaded
     * ActiveState=active
     * SubState=running
     * Description=The PHP FastCGI Process Manager
     * </pre>
     * Description 值本身可能包含 {@code =}，因此仅按首个 {@code =} 拆分。
     *
     * @param unit unit 名
     * @param stdout systemctl 输出
     * @return SystemdUnitStat，无字段时返回带 unit 名的占位实例
     */
    SystemdUnitStat parseShowOutput(String unit, String stdout) {
        SystemdUnitStat stat = new SystemdUnitStat();
        stat.setName(unit);
        if (stdout == null || stdout.isBlank()) {
            return stat;
        }
        for (String rawLine : stdout.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            switch (key) {
                case "LoadState" -> stat.setLoadState(value);
                case "ActiveState" -> stat.setActiveState(value);
                case "SubState" -> stat.setSubState(value);
                case "Description" -> stat.setDescription(value);
                default -> {
                    // 忽略其他字段
                }
            }
        }
        stat.setHealthy("active".equalsIgnoreCase(stat.getActiveState())
                && "running".equalsIgnoreCase(stat.getSubState()));
        return stat;
    }

    /**
     * 解析 application.properties 中的 {@code monitor.collect.systemd.units}，过滤非法 unit 名。
     *
     * @param config 配置
     * @return 合法 unit 列表（按配置顺序、去重）
     */
    private List<String> parseUnits(Properties config) {
        if (config == null) return Collections.emptyList();
        String raw = config.getProperty("monitor.collect.systemd.units", "");
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (String token : Arrays.asList(raw.split(","))) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            if (!UNIT_NAME_PATTERN.matcher(trimmed).matches()) {
                log.warn("systemd unit 名包含非法字符，已丢弃：'{}'", trimmed);
                continue;
            }
            if (!result.contains(trimmed)) {
                result.add(trimmed);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 测试可见：用 lowercase 工具函数避免大小写差异引起断言失败。
     *
     * @param value 字符串
     * @return lowercase 或 null
     */
    static String normalize(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
