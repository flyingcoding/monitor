package org.monitorclient.collector;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.monitorclient.entity.Capabilities;
import org.monitorclient.entity.RuntimeDetail;
import org.monitorclient.system.SystemInfoProvider;
import oshi.software.os.OSProcess;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * {@link ProcessCollector} 单元测试。
 * <p>
 * 沿用项目惯例：手工构造 {@link SystemInfoProvider} fixture 替代 Mockito（避免 JVM attach 依赖）。
 * <p>
 * 设计说明：
 * <ul>
 *   <li>OSHI {@link oshi.SystemInfo} 是 final 类不能继承 / Proxy；为避免在测试中调用 OSHI native 探测，
 *       SystemInfoProvider 返回 null，让 {@link ProcessCollector#enhance} 走早退分支；</li>
 *   <li>核心排序 / 匹配 / 缺失统计逻辑通过 {@link ProcessCollector#buildSnapshot} 直接驱动测试。</li>
 * </ul>
 * 覆盖：
 * <ul>
 *   <li>未配置 patterns → enabled=false，enhance 不写字段；</li>
 *   <li>已配置 patterns → enabled=true / items 顺序保留；</li>
 *   <li>Top N CPU/内存排序正确；</li>
 *   <li>matchPatterns 对 name 与 commandLine 都生效；</li>
 *   <li>缺失 pattern 计数；</li>
 *   <li>非法正则跳过且不破坏其他 pattern；</li>
 *   <li>topN 配置生效 / 越界 / 非法回退；</li>
 *   <li>NaN/负数 CPU 归零；</li>
 *   <li>lastSnapshot 缓存更新；</li>
 *   <li>enhance 对 null runtime 不抛。</li>
 * </ul>
 */
class ProcessCollectorTest {

    /**
     * 构造一个 OSProcess 代理桩，未实现的 getter 返回零值。
     *
     * @param name 进程名
     * @param pid PID
     * @param cpu CPU 使用率（0~1）
     * @param rss RSS 字节
     * @param commandLine 命令行
     * @return OSProcess 代理实例
     */
    static OSProcess fakeProcess(String name, int pid, double cpu, long rss, String commandLine) {
        return (OSProcess) Proxy.newProxyInstance(
                OSProcess.class.getClassLoader(),
                new Class[]{OSProcess.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name == null ? "" : name;
                    case "getProcessID" -> pid;
                    case "getProcessCpuLoadCumulative" -> cpu;
                    case "getResidentSetSize" -> rss;
                    case "getCommandLine" -> commandLine == null ? "" : commandLine;
                    case "toString" -> "FakeProcess[" + name + "/" + pid + "]";
                    case "hashCode" -> pid;
                    case "equals" -> proxy == args[0];
                    default -> defaultForReturnType(method.getReturnType());
                }
        );
    }

    /**
     * 为未实现的 OSProcess 方法返回类型默认值，保证 OSHI Comparator 不抛 NPE。
     *
     * @param type 方法返回类型
     * @return 类型对应的默认值
     */
    private static Object defaultForReturnType(Class<?> type) {
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == boolean.class) return false;
        return null;
    }

    /**
     * 一个 systemInfo() 永远返回 null 的 provider，导致 ProcessCollector 内部走早退分支。
     *
     * @return 测试用 SystemInfoProvider
     */
    private static SystemInfoProvider noOpProvider() {
        return () -> null;
    }

    /**
     * 构造测试用 Properties。
     *
     * @param patterns patterns 配置
     * @param topN topN 配置
     * @return Properties 实例
     */
    private static Properties config(String patterns, String topN) {
        Properties p = new Properties();
        if (patterns != null) p.setProperty(ProcessCollector.KEY_PATTERNS, patterns);
        if (topN != null) p.setProperty(ProcessCollector.KEY_TOP_N, topN);
        return p;
    }

    @Test
    void describe_shouldDisableWhenPatternsEmpty() {
        ProcessCollector collector = new ProcessCollector(noOpProvider(), null, config("", null));
        Capabilities.Module module = collector.describe();
        Assertions.assertFalse(module.isEnabled(), "未配置 patterns 时 enabled=false");
        Assertions.assertTrue(module.isAvailable(), "OSHI 一直可用");
        Assertions.assertNotNull(module.getUnavailableReason());
        Assertions.assertTrue(module.getItems().isEmpty());
    }

    @Test
    void describe_shouldEnableWhenPatternsConfigured() {
        ProcessCollector collector = new ProcessCollector(noOpProvider(), null, config("^java$,^nginx", null));
        Capabilities.Module module = collector.describe();
        Assertions.assertTrue(module.isEnabled());
        Assertions.assertTrue(module.isAvailable());
        Assertions.assertEquals(2, module.getItems().size());
        Assertions.assertEquals("^java$", module.getItems().get(0));
        Assertions.assertEquals("^nginx", module.getItems().get(1));
    }

    @Test
    void enhance_shouldNotWriteWhenDisabled() {
        ProcessCollector collector = new ProcessCollector(noOpProvider(), null, config(null, null));
        RuntimeDetail runtime = new RuntimeDetail();
        collector.enhance(runtime);
        Assertions.assertNull(runtime.getWatchedProcessMissing(), "未启用时 watchedProcessMissing 应为 null");
        Assertions.assertNull(collector.lastSnapshot(), "未启用时不应留下 snapshot");
    }

    @Test
    void buildSnapshot_topByCpuShouldBeSortedDescending() {
        ProcessCollector collector = new ProcessCollector(noOpProvider(), null,
                config("^java$", "3"));
        List<OSProcess> procs = List.of(
                fakeProcess("a", 1, 0.1, 100L, ""),
                fakeProcess("b", 2, 0.5, 200L, ""),
                fakeProcess("c", 3, 0.9, 300L, ""),
                fakeProcess("d", 4, 0.3, 400L, ""),
                fakeProcess("e", 5, 0.7, 500L, "")
        );
        ProcessSnapshot snapshot = collector.buildSnapshot(procs);
        List<ProcessSnapshot.ProcessInfo> top = snapshot.getTop10ByCpu();
        Assertions.assertEquals(3, top.size(), "topN=3 应只返回前 3");
        Assertions.assertEquals("c", top.get(0).getName(), "CPU 最高的进程应排首位");
        Assertions.assertEquals("e", top.get(1).getName());
        Assertions.assertEquals("b", top.get(2).getName());
    }

    @Test
    void buildSnapshot_topByMemoryShouldBeSortedDescending() {
        ProcessCollector collector = new ProcessCollector(noOpProvider(), null,
                config("^java$", "2"));
        List<OSProcess> procs = List.of(
                fakeProcess("a", 1, 0.1, 100L, ""),
                fakeProcess("b", 2, 0.5, 999L, ""),
                fakeProcess("c", 3, 0.9, 500L, "")
        );
        ProcessSnapshot snapshot = collector.buildSnapshot(procs);
        List<ProcessSnapshot.ProcessInfo> top = snapshot.getTop10ByMemory();
        Assertions.assertEquals(2, top.size());
        Assertions.assertEquals("b", top.get(0).getName(), "RSS 最高的进程应排首位");
        Assertions.assertEquals(999L, top.get(0).getMemoryBytes());
        Assertions.assertEquals("c", top.get(1).getName());
    }

    @Test
    void matchPatterns_shouldHitNameOrCommandLine() {
        ProcessCollector collector = new ProcessCollector(noOpProvider(), null,
                config("^java$,worker process", null));
        List<OSProcess> procs = List.of(
                fakeProcess("java", 100, 0.1, 100L, "/usr/bin/java -jar app.jar"),
                fakeProcess("nginx", 200, 0.0, 200L, "nginx: worker process")
        );
        Map<String, Boolean> watched = collector.matchPatterns(procs);
        Assertions.assertTrue(watched.get("^java$"), "name=java 应命中 ^java$");
        Assertions.assertTrue(watched.get("worker process"),
                "command line 应命中 'worker process' 子串 pattern");
    }

    @Test
    void matchPatterns_shouldReportMissingPattern() {
        ProcessCollector collector = new ProcessCollector(noOpProvider(), null,
                config("^java$,^missing$", null));
        List<OSProcess> procs = List.of(
                fakeProcess("java", 100, 0.1, 100L, "")
        );
        Map<String, Boolean> watched = collector.matchPatterns(procs);
        Assertions.assertTrue(watched.get("^java$"));
        Assertions.assertFalse(watched.get("^missing$"));
    }

    @Test
    void buildSnapshot_missingPatternShouldIncrementWatched() {
        ProcessCollector collector = new ProcessCollector(noOpProvider(), null,
                config("^java$,^missing$,^also-missing$", null));
        List<OSProcess> procs = List.of(
                fakeProcess("java", 100, 0.5, 100L, "")
        );
        ProcessSnapshot snapshot = collector.buildSnapshot(procs);
        Map<String, Boolean> watched = snapshot.getWatchedPatterns();
        long missing = watched.values().stream().filter(b -> !b).count();
        Assertions.assertEquals(2, missing, "应有 2 个 pattern 未匹配");
    }

    @Test
    void illegalPattern_shouldBeReplacedByNeverMatchPlaceholder() {
        // [unclosed 是非法正则；ProcessCollector 应回退到占位 pattern 而不是 crash
        ProcessCollector collector = new ProcessCollector(noOpProvider(), null,
                config("[unclosed,^java$", null));
        Capabilities.Module module = collector.describe();
        Assertions.assertEquals(2, module.getItems().size(),
                "非法正则的原始字符串仍出现在 items 中");
        List<OSProcess> procs = List.of(
                fakeProcess("java", 100, 0.1, 100L, "")
        );
        Map<String, Boolean> watched = collector.matchPatterns(procs);
        Assertions.assertFalse(watched.get("[unclosed"), "非法 pattern 应永不命中");
        Assertions.assertTrue(watched.get("^java$"), "合法 pattern 应正常生效");
    }

    @Test
    void topN_shouldClampToMax() {
        ProcessCollector collector = new ProcessCollector(noOpProvider(), null,
                config("^x$", "999"));
        Assertions.assertEquals(ProcessCollector.MAX_TOP_N, collector.topNForTest(),
                "超过最大值应回退到 MAX_TOP_N");
    }

    @Test
    void topN_shouldFallbackToDefaultOnInvalid() {
        ProcessCollector collector = new ProcessCollector(noOpProvider(), null,
                config("^x$", "not-a-number"));
        Assertions.assertEquals(ProcessCollector.DEFAULT_TOP_N, collector.topNForTest());
    }

    @Test
    void topN_negativeShouldFallbackToDefault() {
        ProcessCollector collector = new ProcessCollector(noOpProvider(), null,
                config("^x$", "-5"));
        Assertions.assertEquals(ProcessCollector.DEFAULT_TOP_N, collector.topNForTest());
    }

    @Test
    void cpuOf_shouldClampNegativeAndNaNToZero() {
        ProcessCollector collector = new ProcessCollector(noOpProvider(), null,
                config("^x$", "5"));
        List<OSProcess> procs = new ArrayList<>();
        procs.add(fakeProcess("good", 1, 0.5, 100L, ""));
        procs.add(fakeProcess("nan", 2, Double.NaN, 100L, ""));
        procs.add(fakeProcess("neg", 3, -0.1, 100L, ""));
        procs.add(fakeProcess("inf", 4, Double.POSITIVE_INFINITY, 100L, ""));
        ProcessSnapshot snapshot = collector.buildSnapshot(procs);
        List<ProcessSnapshot.ProcessInfo> top = snapshot.getTop10ByCpu();
        Assertions.assertEquals("good", top.get(0).getName(),
                "正常 CPU 值应排在 NaN/负数/Inf 前");
        for (ProcessSnapshot.ProcessInfo info : top) {
            if (!"good".equals(info.getName())) {
                Assertions.assertEquals(0.0, info.getCpuPercent(), 1e-9);
            }
        }
    }

    @Test
    void enhance_shouldWriteAllMissingWhenNoProcesses() {
        ProcessCollector collector = new ProcessCollector(noOpProvider(), null,
                config("^java$", null));
        // noOpProvider 让 listProcesses() 返回空列表 → 所有 pattern 未命中。
        RuntimeDetail runtime = new RuntimeDetail();
        collector.enhance(runtime);
        Assertions.assertEquals(1, runtime.getWatchedProcessMissing());
    }

    @Test
    void lastSnapshot_shouldBeUpdatedAfterEnhance() {
        ProcessCollector collector = new ProcessCollector(noOpProvider(), null,
                config("^java$", null));
        Assertions.assertNull(collector.lastSnapshot(), "初始无快照");
        RuntimeDetail runtime = new RuntimeDetail();
        collector.enhance(runtime);
        Assertions.assertNotNull(collector.lastSnapshot(), "enhance 后应缓存最新快照");
    }

    @Test
    void enhance_shouldHandleNullRuntimeGracefully() {
        ProcessCollector collector = new ProcessCollector(noOpProvider(), null,
                config("^java$", null));
        // 不应抛
        collector.enhance(null);
    }

    @Test
    void rawPatternsForTest_shouldTrimAndPreserveOrder() {
        ProcessCollector collector = new ProcessCollector(noOpProvider(), null,
                config("a, b , c", null));
        List<String> raws = collector.rawPatternsForTest();
        Assertions.assertEquals(List.of("a", "b", "c"), raws,
                "trim 后保留原始顺序");
    }

    @Test
    void fakeProcess_returnsZerosForUntrackedMethods() {
        // 间接验证 fakeProcess 的 defaultForReturnType 行为
        OSProcess fake = fakeProcess("x", 1, 0.5, 100L, "");
        Assertions.assertEquals(0, fake.getThreadCount());
        Assertions.assertEquals(0L, fake.getUserTime());
        Assertions.assertNull(fake.getPath());
    }
}
