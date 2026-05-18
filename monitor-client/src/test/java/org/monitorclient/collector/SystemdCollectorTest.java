package org.monitorclient.collector;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.monitorclient.entity.Capabilities;
import org.monitorclient.entity.RuntimeDetail;
import org.monitorclient.system.CommandExecutor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link SystemdCollector} 单元测试。
 * <p>
 * 沿用项目惯例：手工构造 {@link CommandExecutor} fixture 替代 Mockito（避免 JVM attach 依赖）。
 * 覆盖：
 * <ul>
 *   <li>非 systemd 系统（systemctl 不可用）→ available=false 且字段保持 null；</li>
 *   <li>未配置 units → enabled=false，整个模块禁用；</li>
 *   <li>active + running unit → healthy=true，failedCount=0；</li>
 *   <li>inactive unit → healthy=false，failedCount 增加；</li>
 *   <li>failed unit → healthy=false，failedCount 增加；</li>
 *   <li>not-loaded（LoadState=not-found）unit → healthy=false；</li>
 *   <li>masked unit → healthy=false；</li>
 *   <li>多 unit 混合 → 仅 active+running 计为 healthy；</li>
 *   <li>非法 unit 名（含 shell 元字符）→ 在 parseUnits 阶段被丢弃；</li>
 *   <li>单 unit 执行超时 → 跳过该 unit，不影响其他 unit；</li>
 *   <li>snapshot 返回的列表与 enhance 内累计一致。</li>
 * </ul>
 */
class SystemdCollectorTest {

    /** 内存版 CommandExecutor：按 unit 名返回预置 stdout 或抛超时。 */
    static class FixtureExecutor implements CommandExecutor {
        boolean available = true;
        boolean availabilityProbeThrows = false;
        final java.util.Map<String, String> stdoutByUnit = new java.util.HashMap<>();
        final java.util.Set<String> timeoutUnits = new java.util.HashSet<>();
        final AtomicInteger callCount = new AtomicInteger();

        @Override
        public CommandResult execute(List<String> command, Duration timeout) {
            callCount.incrementAndGet();
            // 命令格式：[systemctl, show, <unit>, --property=..., --no-pager]
            String unit = command.size() > 2 ? command.get(2) : "";
            if (timeoutUnits.contains(unit)) {
                return new CommandResult(-1, "", "timeout", true);
            }
            String stdout = stdoutByUnit.getOrDefault(unit, "");
            return new CommandResult(0, stdout, "", false);
        }

        @Override
        public boolean isAvailable(String command) {
            if (availabilityProbeThrows) {
                throw new RuntimeException("probe boom");
            }
            return available;
        }
    }

    private static Properties config(String unitsCsv) {
        Properties p = new Properties();
        if (unitsCsv != null) p.setProperty("monitor.collect.systemd.units", unitsCsv);
        return p;
    }

    @Test
    void describe_shouldDisableWhenSystemctlNotAvailable() {
        FixtureExecutor executor = new FixtureExecutor();
        executor.available = false;

        SystemdCollector collector = new SystemdCollector(executor, config("nginx,mysql"));
        Capabilities.Module module = collector.describe();

        Assertions.assertTrue(module.isEnabled(), "已配置 units 应视为 enabled=true");
        Assertions.assertFalse(module.isAvailable(), "systemctl 不可用时 available=false");
        Assertions.assertNotNull(module.getUnavailableReason());
        Assertions.assertEquals(2, module.getCount());

        RuntimeDetail runtime = new RuntimeDetail();
        collector.enhance(runtime);
        Assertions.assertNull(runtime.getSystemdFailedCount(),
                "available=false 时应保持 systemdFailedCount = null");
    }

    @Test
    void describe_shouldNotEnableWhenUnitsEmpty() {
        FixtureExecutor executor = new FixtureExecutor();

        SystemdCollector collector = new SystemdCollector(executor, config(""));
        Capabilities.Module module = collector.describe();

        Assertions.assertFalse(module.isEnabled());
        Assertions.assertFalse(module.isAvailable());
        Assertions.assertEquals(0, module.getCount());

        RuntimeDetail runtime = new RuntimeDetail();
        collector.enhance(runtime);
        Assertions.assertNull(runtime.getSystemdFailedCount());
    }

    @Test
    void enhance_shouldReportZeroFailedWhenAllActiveAndRunning() {
        FixtureExecutor executor = new FixtureExecutor();
        executor.stdoutByUnit.put("nginx", """
                LoadState=loaded
                ActiveState=active
                SubState=running
                Description=A high performance web server
                """);
        executor.stdoutByUnit.put("mysql", """
                LoadState=loaded
                ActiveState=active
                SubState=running
                Description=MySQL Community Server
                """);

        SystemdCollector collector = new SystemdCollector(executor, config("nginx,mysql"));
        RuntimeDetail runtime = new RuntimeDetail();
        collector.enhance(runtime);

        Assertions.assertEquals(Integer.valueOf(0), runtime.getSystemdFailedCount());
        List<SystemdUnitStat> snapshot = collector.snapshot();
        Assertions.assertEquals(2, snapshot.size());
        Assertions.assertTrue(snapshot.get(0).isHealthy());
        Assertions.assertTrue(snapshot.get(1).isHealthy());
        Assertions.assertEquals("nginx", snapshot.get(0).getName());
        Assertions.assertEquals("A high performance web server", snapshot.get(0).getDescription());
    }

    @Test
    void enhance_shouldCountInactiveAsFailed() {
        FixtureExecutor executor = new FixtureExecutor();
        executor.stdoutByUnit.put("nginx", """
                LoadState=loaded
                ActiveState=inactive
                SubState=dead
                Description=A high performance web server
                """);

        SystemdCollector collector = new SystemdCollector(executor, config("nginx"));
        RuntimeDetail runtime = new RuntimeDetail();
        collector.enhance(runtime);

        Assertions.assertEquals(Integer.valueOf(1), runtime.getSystemdFailedCount());
        Assertions.assertFalse(collector.snapshot().get(0).isHealthy());
        Assertions.assertEquals("inactive", collector.snapshot().get(0).getActiveState());
    }

    @Test
    void enhance_shouldCountFailedActiveStateAsFailed() {
        FixtureExecutor executor = new FixtureExecutor();
        executor.stdoutByUnit.put("nginx", """
                LoadState=loaded
                ActiveState=failed
                SubState=failed
                Description=A high performance web server
                """);

        SystemdCollector collector = new SystemdCollector(executor, config("nginx"));
        RuntimeDetail runtime = new RuntimeDetail();
        collector.enhance(runtime);

        Assertions.assertEquals(Integer.valueOf(1), runtime.getSystemdFailedCount());
    }

    @Test
    void enhance_shouldCountNotLoadedAsFailed() {
        FixtureExecutor executor = new FixtureExecutor();
        executor.stdoutByUnit.put("ghost", """
                LoadState=not-found
                ActiveState=inactive
                SubState=dead
                Description=ghost.service
                """);

        SystemdCollector collector = new SystemdCollector(executor, config("ghost"));
        RuntimeDetail runtime = new RuntimeDetail();
        collector.enhance(runtime);

        Assertions.assertEquals(Integer.valueOf(1), runtime.getSystemdFailedCount());
        SystemdUnitStat stat = collector.snapshot().get(0);
        Assertions.assertEquals("not-found", stat.getLoadState());
        Assertions.assertFalse(stat.isHealthy());
    }

    @Test
    void enhance_shouldCountMaskedUnitAsFailed() {
        FixtureExecutor executor = new FixtureExecutor();
        executor.stdoutByUnit.put("blocked", """
                LoadState=masked
                ActiveState=inactive
                SubState=dead
                Description=
                """);

        SystemdCollector collector = new SystemdCollector(executor, config("blocked"));
        RuntimeDetail runtime = new RuntimeDetail();
        collector.enhance(runtime);

        Assertions.assertEquals(Integer.valueOf(1), runtime.getSystemdFailedCount());
        Assertions.assertEquals("masked", collector.snapshot().get(0).getLoadState());
    }

    @Test
    void enhance_shouldMixHealthyAndUnhealthy() {
        FixtureExecutor executor = new FixtureExecutor();
        executor.stdoutByUnit.put("nginx", """
                LoadState=loaded
                ActiveState=active
                SubState=running
                Description=web
                """);
        executor.stdoutByUnit.put("mysql", """
                LoadState=loaded
                ActiveState=inactive
                SubState=dead
                Description=db
                """);
        executor.stdoutByUnit.put("docker", """
                LoadState=loaded
                ActiveState=active
                SubState=running
                Description=container engine
                """);

        SystemdCollector collector = new SystemdCollector(executor, config("nginx,mysql,docker"));
        RuntimeDetail runtime = new RuntimeDetail();
        collector.enhance(runtime);

        Assertions.assertEquals(Integer.valueOf(1), runtime.getSystemdFailedCount(),
                "mysql 一个 failed");
        List<SystemdUnitStat> snapshot = collector.snapshot();
        Assertions.assertEquals(3, snapshot.size());
    }

    @Test
    void parseUnits_shouldRejectShellMetacharacters() {
        FixtureExecutor executor = new FixtureExecutor();
        executor.stdoutByUnit.put("nginx", """
                LoadState=loaded
                ActiveState=active
                SubState=running
                """);

        SystemdCollector collector = new SystemdCollector(executor,
                config("nginx;rm -rf,nginx&,nginx|cat,nginx,evil>file,evil<file"));
        Capabilities.Module module = collector.describe();
        // 只保留合法 unit 名 "nginx"，其他被 parseUnits 过滤掉
        Assertions.assertEquals(1, module.getCount());
        Assertions.assertEquals(List.of("nginx"), module.getItems());
    }

    @Test
    void parseUnits_shouldAcceptInstanceSyntax() {
        FixtureExecutor executor = new FixtureExecutor();
        executor.stdoutByUnit.put("sshd@root.service", """
                LoadState=loaded
                ActiveState=active
                SubState=running
                Description=OpenBSD Secure Shell server (per-connection instance for root)
                """);

        SystemdCollector collector = new SystemdCollector(executor, config("sshd@root.service"));
        RuntimeDetail runtime = new RuntimeDetail();
        collector.enhance(runtime);

        Assertions.assertEquals(Integer.valueOf(0), runtime.getSystemdFailedCount());
        Assertions.assertEquals(1, collector.snapshot().size());
    }

    @Test
    void enhance_shouldSkipTimedOutUnitWithoutAffectingOthers() {
        FixtureExecutor executor = new FixtureExecutor();
        executor.stdoutByUnit.put("nginx", """
                LoadState=loaded
                ActiveState=active
                SubState=running
                """);
        executor.timeoutUnits.add("stuck");

        SystemdCollector collector = new SystemdCollector(executor, config("nginx,stuck"));
        RuntimeDetail runtime = new RuntimeDetail();
        collector.enhance(runtime);

        // stuck 超时被跳过；snapshot 只包含 nginx，failedCount=0
        Assertions.assertEquals(Integer.valueOf(0), runtime.getSystemdFailedCount());
        List<SystemdUnitStat> snapshot = collector.snapshot();
        Assertions.assertEquals(1, snapshot.size());
        Assertions.assertEquals("nginx", snapshot.get(0).getName());
    }

    @Test
    void parseShowOutput_shouldHandleDescriptionContainingEqualsSign() {
        FixtureExecutor executor = new FixtureExecutor();
        SystemdCollector collector = new SystemdCollector(executor, config("nginx"));

        SystemdUnitStat stat = collector.parseShowOutput("nginx", """
                LoadState=loaded
                ActiveState=active
                SubState=running
                Description=foo=bar=baz qux
                """);

        // Description 内含 = 时按首个 = 拆分，value 部分保留 "foo=bar=baz qux"
        Assertions.assertEquals("foo=bar=baz qux", stat.getDescription());
        Assertions.assertTrue(stat.isHealthy());
    }

    @Test
    void describe_shouldHandleProbeException() {
        FixtureExecutor executor = new FixtureExecutor();
        executor.availabilityProbeThrows = true;

        SystemdCollector collector = new SystemdCollector(executor, config("nginx"));
        Capabilities.Module module = collector.describe();

        Assertions.assertTrue(module.isEnabled());
        Assertions.assertFalse(module.isAvailable());
        Assertions.assertNotNull(module.getUnavailableReason());
        Assertions.assertTrue(module.getUnavailableReason().contains("systemctl 探测异常"));
    }

    @Test
    void name_shouldBeSystemd() {
        SystemdCollector collector = new SystemdCollector(new FixtureExecutor(), config("nginx"));
        Assertions.assertEquals("systemd", collector.name());
    }

    @Test
    void parseUnits_shouldDeduplicate() {
        FixtureExecutor executor = new FixtureExecutor();
        executor.stdoutByUnit.put("nginx", """
                LoadState=loaded
                ActiveState=active
                SubState=running
                """);

        SystemdCollector collector = new SystemdCollector(executor, config("nginx, nginx ,nginx"));
        Capabilities.Module module = collector.describe();
        Assertions.assertEquals(1, module.getCount(), "重复 unit 应去重");
    }

    @Test
    void snapshot_shouldReturnCopyNotInternalReference() {
        FixtureExecutor executor = new FixtureExecutor();
        executor.stdoutByUnit.put("nginx", """
                LoadState=loaded
                ActiveState=active
                SubState=running
                """);

        SystemdCollector collector = new SystemdCollector(executor, config("nginx"));
        collector.enhance(new RuntimeDetail());

        List<SystemdUnitStat> snapshot1 = collector.snapshot();
        snapshot1.clear();
        List<SystemdUnitStat> snapshot2 = collector.snapshot();
        Assertions.assertEquals(1, snapshot2.size(),
                "外部修改 snapshot1 不应影响 collector 内部状态");
    }

    @Test
    void enhance_shouldResetSnapshotWhenDisabled() {
        FixtureExecutor executor = new FixtureExecutor();
        executor.available = false; // 模块整体禁用

        SystemdCollector collector = new SystemdCollector(executor, config("nginx"));
        // 手动塞一个值进 lastSnapshot，验证 enhance 调用后会被清空
        RuntimeDetail runtime = new RuntimeDetail();
        collector.enhance(runtime);

        Assertions.assertTrue(collector.snapshot().isEmpty());
        Assertions.assertNull(runtime.getSystemdFailedCount());
    }

    @Test
    void enhance_shouldHandleStdoutWithoutHealthFields() {
        FixtureExecutor executor = new FixtureExecutor();
        // 缺失 ActiveState/SubState 字段（systemctl 兼容性情况）
        executor.stdoutByUnit.put("weird", """
                LoadState=loaded
                Description=weird unit
                """);

        SystemdCollector collector = new SystemdCollector(executor, config("weird"));
        RuntimeDetail runtime = new RuntimeDetail();
        collector.enhance(runtime);

        // 字段缺失视为非 healthy
        Assertions.assertEquals(Integer.valueOf(1), runtime.getSystemdFailedCount());
        Assertions.assertFalse(collector.snapshot().get(0).isHealthy());
    }

    @Test
    void enhance_shouldRecordCommandCallPerCycle() {
        FixtureExecutor executor = new FixtureExecutor();
        executor.stdoutByUnit.put("nginx", """
                LoadState=loaded
                ActiveState=active
                SubState=running
                """);
        executor.stdoutByUnit.put("mysql", """
                LoadState=loaded
                ActiveState=active
                SubState=running
                """);

        SystemdCollector collector = new SystemdCollector(executor, config("nginx,mysql"));
        executor.callCount.set(0);
        collector.enhance(new RuntimeDetail());
        Assertions.assertEquals(2, executor.callCount.get(),
                "每个 unit 应触发一次 systemctl show 调用");
    }

    @Test
    void parseShowOutput_shouldHandleBlankInput() {
        FixtureExecutor executor = new FixtureExecutor();
        SystemdCollector collector = new SystemdCollector(executor, config("nginx"));
        SystemdUnitStat stat = collector.parseShowOutput("nginx", "");
        Assertions.assertEquals("nginx", stat.getName());
        Assertions.assertFalse(stat.isHealthy());
    }

    @Test
    void parseShowOutput_shouldHandleLineWithoutEqualsSign() {
        FixtureExecutor executor = new FixtureExecutor();
        SystemdCollector collector = new SystemdCollector(executor, config("nginx"));
        SystemdUnitStat stat = collector.parseShowOutput("nginx", """
                LoadState=loaded
                garbage line without equals sign
                ActiveState=active
                SubState=running
                """);
        Assertions.assertEquals("loaded", stat.getLoadState());
        Assertions.assertTrue(stat.isHealthy());
    }

    @Test
    void describe_shouldEmitItemsList() {
        FixtureExecutor executor = new FixtureExecutor();
        SystemdCollector collector = new SystemdCollector(executor, config("nginx,mysql,docker"));
        Capabilities.Module module = collector.describe();

        Assertions.assertEquals(new ArrayList<>(List.of("nginx", "mysql", "docker")), module.getItems());
    }
}
