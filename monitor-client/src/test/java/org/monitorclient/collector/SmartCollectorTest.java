package org.monitorclient.collector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.monitorclient.entity.Capabilities;
import org.monitorclient.entity.RuntimeDetail;
import org.monitorclient.system.CommandExecutor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SmartCollector} 单元测试。
 * <p>
 * 沿用项目惯例（参考 {@code AlertEvaluatorImplTest}）：手写测试 stub 替代 Mockito，
 * 通过 {@link FakeCommandExecutor} 注入 {@code smartctl} 命令输出 fixture。
 *
 * <p>覆盖：
 * <ul>
 *   <li>SATA 健康 fixture → criticalCount=0；</li>
 *   <li>SATA 关键异常 fixture → criticalCount=1（reallocated+pending+uncorrectable 任意 &gt; 0）；</li>
 *   <li>NVMe 健康 fixture → criticalCount=0、温度由 Kelvin 转 Celsius；</li>
 *   <li>NVMe media_errors&gt;0 → critical；</li>
 *   <li>命令超时 → 跳过该设备，不算 critical；</li>
 *   <li>命令 exit code 非 0 + 无 stdout → 跳过；</li>
 *   <li>非法 JSON 输出 → 跳过该设备；</li>
 *   <li>smartctl 不可用 → enhance 空操作；</li>
 *   <li>devices 配置为空 → 模块禁用；</li>
 *   <li>设备路径白名单（拒绝非法路径如 {@code /dev/null}，仅保留 {@code /dev/sda}）；</li>
 *   <li>混合健康 + critical → 仅统计 critical 数量；</li>
 *   <li>{@link SmartCollector#lastSnapshot()} 保持最近一次有效结果；</li>
 *   <li>{@link SmartCollector#describe()} 在 smartctl 缺失时填充 unavailableReason。</li>
 * </ul>
 */
class SmartCollectorTest {

    private FakeCommandExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new FakeCommandExecutor();
    }

    /**
     * SATA 健康 fixture：所有关键属性 raw=0 → critical 为 false，温度由 id=194 raw 提取。
     */
    @Test
    void sataHealthyFixtureShouldNotMarkCritical() throws IOException {
        executor.smartctlAvailable = true;
        executor.outputs.put("/dev/sda", new CommandExecutor.CommandResult(
                0, loadFixture("smart-sata-healthy.json"), "", false));
        SmartCollector collector = new SmartCollector(executor, propsWithDevices("/dev/sda"));
        RuntimeDetail rt = new RuntimeDetail();

        collector.enhance(rt);

        assertEquals(0, rt.getSmartCriticalCount());
        List<SmartStat> snap = collector.lastSnapshot();
        assertEquals(1, snap.size());
        SmartStat stat = snap.get(0);
        assertFalse(stat.isCritical());
        assertFalse(stat.isNvme());
        assertEquals("Samsung SSD 850 EVO 500GB", stat.getModelName());
        assertEquals(0L, stat.getReallocatedSector());
        assertEquals(0L, stat.getCurrentPending());
        assertEquals(0L, stat.getOfflineUncorrectable());
        assertEquals(35, stat.getTemperatureCelsius());
    }

    /**
     * SATA 异常 fixture：reallocated/pending/uncorrectable 均 &gt; 0 → critical=true。
     */
    @Test
    void sataCriticalFixtureShouldMarkCritical() throws IOException {
        executor.smartctlAvailable = true;
        executor.outputs.put("/dev/sdb", new CommandExecutor.CommandResult(
                0, loadFixture("smart-sata-critical.json"), "", false));
        SmartCollector collector = new SmartCollector(executor, propsWithDevices("/dev/sdb"));
        RuntimeDetail rt = new RuntimeDetail();

        collector.enhance(rt);

        assertEquals(1, rt.getSmartCriticalCount());
        SmartStat stat = collector.lastSnapshot().get(0);
        assertTrue(stat.isCritical());
        assertEquals(42L, stat.getReallocatedSector());
        assertEquals(4L, stat.getCurrentPending());
        assertEquals(1L, stat.getOfflineUncorrectable());
        assertEquals(78, stat.getTemperatureCelsius());
    }

    /**
     * NVMe 健康 fixture：温度 313K → 40℃；media_errors=0 不算 critical。
     */
    @Test
    void nvmeHealthyFixtureShouldConvertKelvinTemperature() throws IOException {
        executor.smartctlAvailable = true;
        executor.outputs.put("/dev/nvme0n1", new CommandExecutor.CommandResult(
                0, loadFixture("smart-nvme-healthy.json"), "", false));
        SmartCollector collector = new SmartCollector(executor, propsWithDevices("/dev/nvme0n1"));
        RuntimeDetail rt = new RuntimeDetail();

        collector.enhance(rt);

        assertEquals(0, rt.getSmartCriticalCount());
        SmartStat stat = collector.lastSnapshot().get(0);
        assertTrue(stat.isNvme());
        assertFalse(stat.isCritical());
        assertEquals(0L, stat.getMediaErrors());
        assertEquals(40, stat.getTemperatureCelsius(), "313 Kelvin should convert to 40 Celsius");
    }

    /**
     * NVMe media_errors&gt;0 → critical=true。
     */
    @Test
    void nvmeMediaErrorsShouldMarkCritical() throws IOException {
        executor.smartctlAvailable = true;
        executor.outputs.put("/dev/nvme1n1", new CommandExecutor.CommandResult(
                0, loadFixture("smart-nvme-critical.json"), "", false));
        SmartCollector collector = new SmartCollector(executor, propsWithDevices("/dev/nvme1n1"));
        RuntimeDetail rt = new RuntimeDetail();

        collector.enhance(rt);

        assertEquals(1, rt.getSmartCriticalCount());
        SmartStat stat = collector.lastSnapshot().get(0);
        assertTrue(stat.isCritical());
        assertEquals(17L, stat.getMediaErrors());
        assertEquals(75, stat.getTemperatureCelsius());
    }

    /**
     * 命令超时 → 跳过该设备，不计入 critical，snapshot 不包含该 device。
     */
    @Test
    void timeoutShouldSkipDevice() {
        executor.smartctlAvailable = true;
        executor.outputs.put("/dev/sda", new CommandExecutor.CommandResult(
                -1, "", "timed out", true));
        SmartCollector collector = new SmartCollector(executor, propsWithDevices("/dev/sda"));
        RuntimeDetail rt = new RuntimeDetail();

        collector.enhance(rt);

        assertEquals(0, rt.getSmartCriticalCount());
        assertTrue(collector.lastSnapshot().isEmpty());
    }

    /**
     * 命令 exit code 非 0 + 无 stdout → 跳过该设备。
     */
    @Test
    void emptyStdoutShouldSkipDevice() {
        executor.smartctlAvailable = true;
        executor.outputs.put("/dev/sda", new CommandExecutor.CommandResult(
                1, "", "smartctl error", false));
        SmartCollector collector = new SmartCollector(executor, propsWithDevices("/dev/sda"));
        RuntimeDetail rt = new RuntimeDetail();

        collector.enhance(rt);

        assertEquals(0, rt.getSmartCriticalCount());
        assertTrue(collector.lastSnapshot().isEmpty());
    }

    /**
     * 非 JSON stdout → 解析失败，跳过该设备但不抛异常。
     */
    @Test
    void invalidJsonShouldSkipDeviceWithoutThrowing() {
        executor.smartctlAvailable = true;
        executor.outputs.put("/dev/sda", new CommandExecutor.CommandResult(
                0, "not a valid json {{{", "", false));
        SmartCollector collector = new SmartCollector(executor, propsWithDevices("/dev/sda"));
        RuntimeDetail rt = new RuntimeDetail();

        collector.enhance(rt);

        assertEquals(0, rt.getSmartCriticalCount());
        assertTrue(collector.lastSnapshot().isEmpty());
    }

    /**
     * smartctl 不可用 → describe 标记 available=false 并填充原因；enhance 空操作。
     */
    @Test
    void unavailableSmartctlShouldDisableCollector() {
        executor.smartctlAvailable = false;
        SmartCollector collector = new SmartCollector(executor, propsWithDevices("/dev/sda"));
        Capabilities.Module module = collector.describe();

        assertTrue(module.isEnabled());
        assertFalse(module.isAvailable());
        assertNotNull(module.getUnavailableReason());

        RuntimeDetail rt = new RuntimeDetail();
        collector.enhance(rt);
        assertNull(rt.getSmartCriticalCount(),
                "smartctl 不可用时不应写入聚合 metric");
        assertEquals(0, executor.executeCount,
                "smartctl 不可用时不应触发 enhance 中的命令调用");
    }

    /**
     * devices 配置为空 → 模块整体禁用。
     */
    @Test
    void emptyDevicesShouldDisableModule() {
        executor.smartctlAvailable = true;
        SmartCollector collector = new SmartCollector(executor, new Properties());
        Capabilities.Module module = collector.describe();

        assertFalse(module.isEnabled());
        assertEquals(0, module.getCount());
        assertEquals(0, module.getItems().size());

        RuntimeDetail rt = new RuntimeDetail();
        collector.enhance(rt);
        assertNull(rt.getSmartCriticalCount());
    }

    /**
     * 非法设备路径（如 {@code /dev/null} / shell 注入）应被过滤。
     */
    @Test
    void illegalDevicePathShouldBeRejected() {
        executor.smartctlAvailable = true;
        Properties props = new Properties();
        props.setProperty("monitor.collect.smart.devices",
                "/dev/sda, /dev/null, /etc/passwd, /dev/sdb; rm -rf /, /dev/nvme0n1");
        SmartCollector collector = new SmartCollector(executor, props);

        Capabilities.Module module = collector.describe();
        assertEquals(List.of("/dev/sda", "/dev/nvme0n1"), module.getItems(),
                "白名单仅保留合法路径");
    }

    /**
     * 混合健康 + critical 多设备 → criticalCount 只计 critical。
     */
    @Test
    void mixedDevicesShouldCountCriticalOnly() throws IOException {
        executor.smartctlAvailable = true;
        executor.outputs.put("/dev/sda", new CommandExecutor.CommandResult(
                0, loadFixture("smart-sata-healthy.json"), "", false));
        executor.outputs.put("/dev/sdb", new CommandExecutor.CommandResult(
                0, loadFixture("smart-sata-critical.json"), "", false));
        executor.outputs.put("/dev/nvme0n1", new CommandExecutor.CommandResult(
                0, loadFixture("smart-nvme-healthy.json"), "", false));
        executor.outputs.put("/dev/nvme1n1", new CommandExecutor.CommandResult(
                0, loadFixture("smart-nvme-critical.json"), "", false));
        Properties props = new Properties();
        props.setProperty("monitor.collect.smart.devices",
                "/dev/sda,/dev/sdb,/dev/nvme0n1,/dev/nvme1n1");
        SmartCollector collector = new SmartCollector(executor, props);
        RuntimeDetail rt = new RuntimeDetail();

        collector.enhance(rt);

        assertEquals(2, rt.getSmartCriticalCount());
        assertEquals(4, collector.lastSnapshot().size());
    }

    /**
     * 后续 enhance 调用应覆盖 lastSnapshot；模拟设备瞬时失败后再恢复的场景。
     */
    @Test
    void enhanceShouldRefreshLastSnapshot() throws IOException {
        executor.smartctlAvailable = true;
        executor.outputs.put("/dev/sda", new CommandExecutor.CommandResult(
                0, loadFixture("smart-sata-healthy.json"), "", false));
        SmartCollector collector = new SmartCollector(executor, propsWithDevices("/dev/sda"));
        RuntimeDetail first = new RuntimeDetail();
        collector.enhance(first);
        assertEquals(0, first.getSmartCriticalCount());

        // 第二次执行：设备出现 reallocated 异常
        executor.outputs.put("/dev/sda", new CommandExecutor.CommandResult(
                0, loadFixture("smart-sata-critical.json"), "", false));
        RuntimeDetail second = new RuntimeDetail();
        collector.enhance(second);
        assertEquals(1, second.getSmartCriticalCount());
        assertTrue(collector.lastSnapshot().get(0).isCritical());
    }

    /**
     * {@link SmartCollector#describe()} 在 enabled+available 时返回 devices 数量与 reason=null。
     */
    @Test
    void describeShouldReturnCapabilityMetadata() {
        executor.smartctlAvailable = true;
        Properties props = new Properties();
        props.setProperty("monitor.collect.smart.devices", "/dev/sda,/dev/nvme0n1");
        SmartCollector collector = new SmartCollector(executor, props);
        Capabilities.Module module = collector.describe();

        assertTrue(module.isEnabled());
        assertTrue(module.isAvailable());
        assertEquals(2, module.getCount());
        assertEquals(List.of("/dev/sda", "/dev/nvme0n1"), module.getItems());
        assertNull(module.getUnavailableReason());
    }

    /**
     * 加载 fixture 资源文件。
     *
     * @param name 资源名
     * @return 文件内容
     * @throws IOException 读取失败
     */
    private String loadFixture(String name) throws IOException {
        try (var is = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(is, "missing fixture: " + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 构造单 device 配置。
     *
     * @param device 设备路径
     * @return Properties
     */
    private Properties propsWithDevices(String device) {
        Properties props = new Properties();
        props.setProperty("monitor.collect.smart.devices", device);
        return props;
    }

    /**
     * 简单 stub：根据命令首参 device 路径返回预置 {@link CommandExecutor.CommandResult}。
     */
    private static class FakeCommandExecutor implements CommandExecutor {
        final Map<String, CommandResult> outputs = new HashMap<>();
        boolean smartctlAvailable;
        int executeCount = 0;
        final List<List<String>> executedCommands = new ArrayList<>();

        @Override
        public CommandResult execute(List<String> command, Duration timeout) {
            executeCount++;
            executedCommands.add(command);
            // smartctl -A -j <device>
            String device = command.size() >= 4 ? command.get(3) : "";
            CommandResult result = outputs.get(device);
            if (result == null) {
                return new CommandResult(-1, "", "device not configured", false);
            }
            return result;
        }

        @Override
        public boolean isAvailable(String command) {
            return "smartctl".equals(command) && smartctlAvailable;
        }
    }
}
