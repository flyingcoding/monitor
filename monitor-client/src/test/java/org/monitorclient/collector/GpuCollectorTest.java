package org.monitorclient.collector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.monitorclient.entity.Capabilities;
import org.monitorclient.entity.RuntimeDetail;
import org.monitorclient.system.CommandExecutor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GpuCollector} 单元测试。
 * <p>
 * 沿用项目惯例（参考 {@code SmartCollectorTest}）：手写测试 stub 替代 Mockito，
 * 通过 {@link FakeCommandExecutor} 注入 {@code nvidia-smi} 命令输出 fixture。
 *
 * <p>覆盖：
 * <ul>
 *   <li>单 GPU 解析 + 聚合最高温度；</li>
 *   <li>多 GPU 解析 + 选出最高温；</li>
 *   <li>解析失败行跳过，其余正常返回；</li>
 *   <li>命令失败（exit code 非 0）→ rt.gpuTemperatureMax 保持 null；</li>
 *   <li>命令超时 → rt.gpuTemperatureMax 保持 null；</li>
 *   <li>enabled=false 时不调用 executor；</li>
 *   <li>nvidia-smi 不可用 → Capabilities.unavailableReason 提示；</li>
 *   <li>{@link GpuCollector#describe()} 在可用时填充 count 与 enabled=true；</li>
 *   <li>{@link GpuCollector#lastSnapshot()} 返回最近一次有效结果；</li>
 *   <li>{@code [N/A]} 字段填充 null 而不抛出。</li>
 * </ul>
 */
class GpuCollectorTest {

    private FakeCommandExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new FakeCommandExecutor();
    }

    /**
     * 单 GPU 输出应正确解析并聚合最高温度。
     */
    @Test
    void singleGpuShouldParseAndAggregateMaxTemperature() {
        executor.nvidiaSmiAvailable = true;
        executor.queryOutput = "0, NVIDIA GeForce RTX 4090, 45, 4096, 24576, 65.5, 250.5\n";
        executor.gpuListOutput = "GPU 0: NVIDIA GeForce RTX 4090 (UUID: GPU-xxxx)\n";
        GpuCollector collector = new GpuCollector(executor, propsEnabled());
        RuntimeDetail rt = new RuntimeDetail();

        collector.enhance(rt);

        assertEquals(65.5, rt.getGpuTemperatureMax());
        List<GpuStat> snapshot = collector.lastSnapshot();
        assertEquals(1, snapshot.size());
        GpuStat stat = snapshot.get(0);
        assertEquals(0, stat.getIndex());
        assertEquals("NVIDIA GeForce RTX 4090", stat.getName());
        assertEquals(45.0, stat.getUtilizationPercent());
        assertEquals(4096.0, stat.getMemoryUsedMb());
        assertEquals(24576.0, stat.getMemoryTotalMb());
        assertEquals(65.5, stat.getTemperatureCelsius());
        assertEquals(250.5, stat.getPowerDrawWatts());
    }

    /**
     * 多 GPU 应分别解析并聚合最高温度。
     */
    @Test
    void multiGpuShouldAggregateMaxTemperature() {
        executor.nvidiaSmiAvailable = true;
        executor.queryOutput = """
                0, NVIDIA GeForce RTX 4090, 30, 2048, 24576, 60.0, 180.0
                1, NVIDIA GeForce RTX 4090, 95, 22000, 24576, 82.5, 420.0
                2, NVIDIA GeForce RTX 4090, 12, 1024, 24576, 55.0, 95.0
                """;
        executor.gpuListOutput = """
                GPU 0: NVIDIA GeForce RTX 4090 (UUID: GPU-aaa)
                GPU 1: NVIDIA GeForce RTX 4090 (UUID: GPU-bbb)
                GPU 2: NVIDIA GeForce RTX 4090 (UUID: GPU-ccc)
                """;
        GpuCollector collector = new GpuCollector(executor, propsEnabled());
        RuntimeDetail rt = new RuntimeDetail();

        collector.enhance(rt);

        assertEquals(82.5, rt.getGpuTemperatureMax(), "最高温应取中间 GPU 1 的 82.5");
        assertEquals(3, collector.lastSnapshot().size());
        // describe() 应得到 count=3（基于 nvidia-smi -L 的输出）
        assertEquals(3, collector.describe().getCount());
    }

    /**
     * 字段不足 7 个的行应被跳过，其余正常返回。
     */
    @Test
    void malformedLineShouldBeSkipped() {
        executor.nvidiaSmiAvailable = true;
        executor.queryOutput = """
                0, NVIDIA GeForce RTX 4090, 30, 2048, 24576, 60.0, 180.0
                garbage data line
                1, NVIDIA GeForce RTX 3090, 80, 8000, 24576, 75.0, 350.0
                """;
        executor.gpuListOutput = "GPU 0: NVIDIA GeForce RTX 4090 (UUID: GPU-aaa)\n"
                + "GPU 1: NVIDIA GeForce RTX 3090 (UUID: GPU-bbb)\n";
        GpuCollector collector = new GpuCollector(executor, propsEnabled());
        RuntimeDetail rt = new RuntimeDetail();

        collector.enhance(rt);

        // 仅 2 行成功解析；max 取两者中的 75.0
        assertEquals(75.0, rt.getGpuTemperatureMax());
        assertEquals(2, collector.lastSnapshot().size());
    }

    /**
     * 命令失败时，rt.gpuTemperatureMax 应保持 null，lastSnapshot 清空。
     */
    @Test
    void commandFailureShouldKeepNullAndClearSnapshot() {
        executor.nvidiaSmiAvailable = true;
        executor.queryResult = new CommandExecutor.CommandResult(1, "", "Driver not loaded", false);
        executor.gpuListOutput = "GPU 0: NVIDIA GeForce RTX 4090 (UUID: GPU-aaa)\n";
        GpuCollector collector = new GpuCollector(executor, propsEnabled());
        RuntimeDetail rt = new RuntimeDetail();

        collector.enhance(rt);

        assertNull(rt.getGpuTemperatureMax(), "命令失败时 rt 字段保持 null");
        assertTrue(collector.lastSnapshot().isEmpty());
    }

    /**
     * 命令超时也应静默处理（rt 保持 null）。
     */
    @Test
    void commandTimeoutShouldKeepNull() {
        executor.nvidiaSmiAvailable = true;
        executor.queryResult = new CommandExecutor.CommandResult(-1, "", "timeout", true);
        executor.gpuListOutput = "GPU 0: NVIDIA GeForce RTX 4090 (UUID: GPU-aaa)\n";
        GpuCollector collector = new GpuCollector(executor, propsEnabled());
        RuntimeDetail rt = new RuntimeDetail();

        collector.enhance(rt);

        assertNull(rt.getGpuTemperatureMax());
        assertTrue(collector.lastSnapshot().isEmpty());
    }

    /**
     * enabled=false 时不应调用 executor.execute / isAvailable，rt 字段保持 null。
     */
    @Test
    void disabledCollectorShouldNotCallExecutor() {
        executor.nvidiaSmiAvailable = true;
        Properties props = new Properties();
        // 显式禁用（也是默认值）
        props.setProperty(GpuCollector.CONFIG_KEY_ENABLED, "false");
        GpuCollector collector = new GpuCollector(executor, props);

        RuntimeDetail rt = new RuntimeDetail();
        collector.enhance(rt);

        assertNull(rt.getGpuTemperatureMax());
        assertEquals(0, executor.executeCount.get(), "禁用时不应执行任何命令");

        Capabilities.Module module = collector.describe();
        assertFalse(module.isEnabled());
        assertFalse(module.isAvailable());
        assertNull(module.getCount());
    }

    /**
     * 启用但 nvidia-smi 未检测到 → available=false + unavailableReason。
     */
    @Test
    void enabledButToolUnavailableShouldReportReason() {
        executor.nvidiaSmiAvailable = false;
        GpuCollector collector = new GpuCollector(executor, propsEnabled());

        Capabilities.Module module = collector.describe();
        assertTrue(module.isEnabled());
        assertFalse(module.isAvailable());
        assertEquals("nvidia-smi 未检测到", module.getUnavailableReason());

        RuntimeDetail rt = new RuntimeDetail();
        collector.enhance(rt);
        assertNull(rt.getGpuTemperatureMax());
    }

    /**
     * 部分字段为 {@code [N/A]} 应解析为 null 而不丢整行；温度若为 N/A 不计入最高温聚合。
     */
    @Test
    void naFieldsShouldParseAsNullAndSkipTemperature() {
        executor.nvidiaSmiAvailable = true;
        executor.queryOutput = """
                0, NVIDIA Tesla T4, [N/A], 1024, 16384, [N/A], [N/A]
                1, NVIDIA Tesla V100, 50, 4096, 32768, 70.0, 300.0
                """;
        executor.gpuListOutput = """
                GPU 0: NVIDIA Tesla T4 (UUID: GPU-t4)
                GPU 1: NVIDIA Tesla V100 (UUID: GPU-v100)
                """;
        GpuCollector collector = new GpuCollector(executor, propsEnabled());
        RuntimeDetail rt = new RuntimeDetail();

        collector.enhance(rt);

        // GPU 0 的 temperature.gpu 为 [N/A]，应被过滤；max 取 GPU 1 的 70.0
        assertEquals(70.0, rt.getGpuTemperatureMax());
        List<GpuStat> snapshot = collector.lastSnapshot();
        assertEquals(2, snapshot.size());
        GpuStat first = snapshot.get(0);
        assertEquals(0, first.getIndex());
        assertNull(first.getUtilizationPercent());
        assertNull(first.getTemperatureCelsius());
        assertNull(first.getPowerDrawWatts());
        assertEquals(1024.0, first.getMemoryUsedMb());
    }

    /**
     * 后续 enhance 应覆盖 lastSnapshot；模拟一次成功后再失败的场景。
     */
    @Test
    void enhanceShouldRefreshLastSnapshot() {
        executor.nvidiaSmiAvailable = true;
        executor.queryOutput = "0, NVIDIA GeForce RTX 4090, 50, 4096, 24576, 60.0, 200.0\n";
        executor.gpuListOutput = "GPU 0: NVIDIA GeForce RTX 4090 (UUID: GPU-xxx)\n";
        GpuCollector collector = new GpuCollector(executor, propsEnabled());
        RuntimeDetail first = new RuntimeDetail();
        collector.enhance(first);
        assertEquals(60.0, first.getGpuTemperatureMax());
        assertEquals(1, collector.lastSnapshot().size());

        // 第二次：命令失败 → lastSnapshot 应被清空
        executor.queryResult = new CommandExecutor.CommandResult(127, "", "command not found", false);
        executor.queryOutput = null;
        RuntimeDetail second = new RuntimeDetail();
        collector.enhance(second);
        assertNull(second.getGpuTemperatureMax());
        assertTrue(collector.lastSnapshot().isEmpty(), "命令失败应清空 lastSnapshot");
    }

    /**
     * {@link GpuCollector#name()} 返回 {@code "gpu"} 用于 capabilities JSON 的 key 路由。
     */
    @Test
    void nameShouldBeGpu() {
        GpuCollector collector = new GpuCollector(executor, propsEnabled());
        assertEquals("gpu", collector.name());
    }

    /**
     * 构造启用配置。
     *
     * @return Properties
     */
    private Properties propsEnabled() {
        Properties props = new Properties();
        props.setProperty(GpuCollector.CONFIG_KEY_ENABLED, "true");
        return props;
    }

    /**
     * 简单 stub：根据命令首参（{@code -L} 或 {@code --query-gpu=...}）返回预置结果。
     */
    private static class FakeCommandExecutor implements CommandExecutor {
        boolean nvidiaSmiAvailable;
        String queryOutput;
        String gpuListOutput;
        CommandResult queryResult;  // 优先级高于 queryOutput
        final AtomicInteger executeCount = new AtomicInteger(0);
        final List<List<String>> executedCommands = new ArrayList<>();
        final Map<String, Integer> isAvailableCalls = new HashMap<>();

        @Override
        public CommandResult execute(List<String> command, Duration timeout) {
            executeCount.incrementAndGet();
            executedCommands.add(command);
            // 区分 -L 与 --query-gpu
            if (command.size() >= 2 && "-L".equals(command.get(1))) {
                if (gpuListOutput == null) {
                    return new CommandResult(1, "", "no GPUs", false);
                }
                return new CommandResult(0, gpuListOutput, "", false);
            }
            if (queryResult != null) {
                return queryResult;
            }
            if (queryOutput != null) {
                return new CommandResult(0, queryOutput, "", false);
            }
            return new CommandResult(1, "", "no fixture", false);
        }

        @Override
        public boolean isAvailable(String command) {
            isAvailableCalls.merge(command, 1, Integer::sum);
            return "nvidia-smi".equals(command) && nvidiaSmiAvailable;
        }
    }
}
