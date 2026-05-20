package com.example.tsdb;

import com.alibaba.fastjson2.JSON;
import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.entity.vo.response.RuntimeHistoryVO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@link InfluxDbProvider} 的本地缓冲与回放路径单元测试。
 *
 * <p>不依赖真实 InfluxDB。覆盖：
 * <ul>
 *   <li>{@code appendBufferRecord} 写入 JSONL 文件且字段往返可解析；</li>
 *   <li>缓冲文件命名形如 {@code <millis>-<uuid>.jsonl}；</li>
 *   <li>{@code archiveFile} 把成功重放的文件移到 {@code archive/} 子目录；</li>
 *   <li>{@code replaySingleFile} 在文件无效时返回 false 并保留原文件等待下次重试。</li>
 * </ul>
 *
 * <p>测试通过反射调用 private 方法以避免改动生产签名；与项目内 {@code ProbeSchedulerTest} 的
 * 反射用法对齐（spec 允许工具型类的内部辅助方法保持 private）。
 */
class InfluxDbProviderBufferTest {

    @TempDir
    Path tempDir;

    private InfluxDbProvider provider;

    @BeforeEach
    void setUp() {
        provider = new InfluxDbProvider();
        ReflectionTestUtils.setField(provider, "bufferDir", tempDir.toString());
        ReflectionTestUtils.setField(provider, "replayBatchSize", 10);
        invoke("ensureBufferDirectories");
    }

    @Test
    void appendBufferRecordShouldWriteJsonlAndPreserveFields() throws Exception {
        RuntimeDetailVO vo = new RuntimeDetailVO();
        ReflectionTestUtils.setField(vo, "timestamp", 1_700_000_000_000L);
        ReflectionTestUtils.setField(vo, "cpuUsage", 0.42);
        ReflectionTestUtils.setField(vo, "memoryUsage", 8.0);
        ReflectionTestUtils.setField(vo, "diskUsage", 100.0);
        ReflectionTestUtils.setField(vo, "networkUpload", 12.5);
        ReflectionTestUtils.setField(vo, "networkDownload", 25.0);
        ReflectionTestUtils.setField(vo, "diskRead", 1.0);
        ReflectionTestUtils.setField(vo, "diskWrite", 2.0);

        invoke("appendBufferRecord", new Class[]{int.class, RuntimeDetailVO.class}, 42, vo);

        try (Stream<Path> files = Files.list(tempDir)) {
            List<Path> jsonl = files
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .toList();
            Assertions.assertEquals(1, jsonl.size(), "应生成单个 JSONL 缓冲文件");
            String name = jsonl.get(0).getFileName().toString();
            Assertions.assertTrue(name.matches("\\d+-[0-9a-fA-F\\-]+\\.jsonl"),
                    "缓冲文件名需符合 <millis>-<uuid>.jsonl 约定，实际：" + name);

            String content = Files.readString(jsonl.get(0), StandardCharsets.UTF_8).trim();
            InfluxDbProvider.TsdbBufferRecord record =
                    JSON.parseObject(content, InfluxDbProvider.TsdbBufferRecord.class);
            Assertions.assertEquals(42, record.getClientId());
            Assertions.assertNotNull(record.getRuntime());
            Assertions.assertEquals(0.42, record.getRuntime().getCpuUsage(), 1e-9);
            Assertions.assertEquals(1_700_000_000_000L, record.getRuntime().getTimestamp());
            Assertions.assertTrue(record.getBufferedAt() > 0);
        }
    }

    @Test
    void replaySingleFileShouldReturnFalseAndKeepFileWhenContentInvalid() throws Exception {
        Path bad = tempDir.resolve("1700000000000-invalid.jsonl");
        Files.writeString(bad, "{not valid json", StandardCharsets.UTF_8);

        Boolean ok = (Boolean) invoke("replaySingleFile", new Class[]{Path.class}, bad);
        Path archived = tempDir.resolve("archive").resolve(bad.getFileName());
        Assertions.assertEquals(Boolean.FALSE, ok, "非法 JSONL 必须返回 false，等待下次重试");
        Assertions.assertTrue(Files.exists(bad), "失败重放必须保留原文件，避免数据丢失");
        Assertions.assertFalse(Files.exists(archived), "失败重放不能归档坏文件");
    }

    @Test
    void replaySingleFileShouldUseActiveAdapterWhenVictoriaMetricsConfigured() throws Exception {
        RuntimeDetailVO vo = sampleVo();
        InfluxDbProvider.TsdbBufferRecord record = new InfluxDbProvider.TsdbBufferRecord();
        record.setClientId(42);
        record.setRuntime(vo);
        record.setBufferedAt(1_700_000_000_000L);
        Path file = tempDir.resolve("1700000000000-vm-replay.jsonl");
        Files.writeString(file, JSON.toJSONString(record) + System.lineSeparator(), StandardCharsets.UTF_8);

        RecordingAdapter activeAdapter = new RecordingAdapter();
        ReflectionTestUtils.setField(provider, "activeProvider", TsdbAdapterFactory.PROVIDER_VICTORIA_METRICS);
        ReflectionTestUtils.setField(provider, "activeAdapterProvider", objectProvider(activeAdapter));

        Boolean ok = (Boolean) invoke("replaySingleFile", new Class[]{Path.class}, file);

        Assertions.assertEquals(Boolean.TRUE, ok, "VM 模式下重放成功后应归档原文件");
        Assertions.assertEquals(1, activeAdapter.writeCount, "VM 模式下必须通过当前 active adapter 重放");
        Assertions.assertEquals(42, activeAdapter.lastClientId);
        Assertions.assertEquals(vo.getTimestamp(), activeAdapter.lastRuntime.getTimestamp());
        Assertions.assertEquals(vo.getCpuUsage(), activeAdapter.lastRuntime.getCpuUsage(), 1e-9);
        Assertions.assertTrue(Files.exists(tempDir.resolve("archive").resolve(file.getFileName())),
                "重放成功的文件应移动到 archive");
    }

    @Test
    void archiveDirectoryShouldExistAfterEnsureBufferDirectories() {
        Path archive = tempDir.resolve("archive");
        Assertions.assertTrue(Files.exists(archive), "ensureBufferDirectories 必须创建 archive 子目录");
        Assertions.assertTrue(Files.isDirectory(archive));
    }

    @Test
    void appendBufferShouldAccumulateMultipleRecordsInSeparateFiles() throws Exception {
        for (int i = 0; i < 3; i++) {
            RuntimeDetailVO vo = new RuntimeDetailVO();
            ReflectionTestUtils.setField(vo, "timestamp", System.currentTimeMillis());
            invoke("appendBufferRecord", new Class[]{int.class, RuntimeDetailVO.class}, i, vo);
        }
        try (Stream<Path> files = Files.list(tempDir)) {
            long count = files.filter(p -> p.getFileName().toString().endsWith(".jsonl")).count();
            Assertions.assertEquals(3, count, "3 次降级应生成 3 个独立缓冲文件，便于按时间顺序重放");
        }
    }

    @Test
    void migrateLegacyBufferShouldMoveJsonlFilesFromOldDirectoryToNew() throws Exception {
        // 准备：模拟 v1.x / v2.0-alpha 的旧目录 data/influx-buffer/
        Path legacyDir = tempDir.resolve("legacy-influx-buffer");
        Path legacyArchive = legacyDir.resolve("archive");
        Files.createDirectories(legacyArchive);
        Path legacyJsonl = legacyDir.resolve("1700000000000-aaa.jsonl");
        Path legacyArchivedJsonl = legacyArchive.resolve("1690000000000-bbb.jsonl");
        Files.writeString(legacyJsonl, "{\"clientId\":1}", StandardCharsets.UTF_8);
        Files.writeString(legacyArchivedJsonl, "{\"clientId\":2}", StandardCharsets.UTF_8);

        // 新目录：使用 tempDir 子目录作为 bufferDir
        Path newDir = tempDir.resolve("new-tsdb-buffer");
        ReflectionTestUtils.setField(provider, "bufferDir", newDir.toString());
        // 通过反射改 LEGACY 常量不可行，转而显式调 moveJsonlFiles 验证 helper 语义
        invoke("ensureBufferDirectories");
        Method moveMethod = InfluxDbProvider.class.getDeclaredMethod("moveJsonlFiles", Path.class, Path.class);
        moveMethod.setAccessible(true);
        int movedRoot = (Integer) moveMethod.invoke(provider, legacyDir, newDir);
        int movedArchive = (Integer) moveMethod.invoke(provider, legacyArchive, newDir.resolve("archive"));

        Assertions.assertEquals(1, movedRoot, "根目录 JSONL 应被迁移到新目录");
        Assertions.assertEquals(1, movedArchive, "archive 子目录 JSONL 应被迁移到新 archive 子目录");
        Assertions.assertTrue(Files.exists(newDir.resolve(legacyJsonl.getFileName())),
                "迁移后新目录必须保留同名文件");
        Assertions.assertTrue(Files.exists(newDir.resolve("archive").resolve(legacyArchivedJsonl.getFileName())),
                "迁移后新 archive 子目录必须保留同名文件");
        Assertions.assertFalse(Files.exists(legacyJsonl), "迁移后旧文件应已移除");
    }

    @Test
    void migrateLegacyBufferShouldUseConfiguredLegacyDirectory() throws Exception {
        Path legacyDir = tempDir.resolve("custom-old-buffer");
        Files.createDirectories(legacyDir);
        Path legacyJsonl = legacyDir.resolve("1700000000000-custom.jsonl");
        Files.writeString(legacyJsonl, "{\"clientId\":9}", StandardCharsets.UTF_8);

        Path newDir = tempDir.resolve("new-buffer-from-config");
        ReflectionTestUtils.setField(provider, "bufferDir", newDir.toString());
        ReflectionTestUtils.setField(provider, "legacyConfiguredBufferDir", legacyDir.toString());
        invoke("ensureBufferDirectories");

        invoke("migrateLegacyBufferIfNeeded");

        Assertions.assertTrue(Files.exists(newDir.resolve(legacyJsonl.getFileName())),
                "显式配置的旧 monitor.influx-buffer.dir 应迁移到新 TSDB 缓冲目录");
        Assertions.assertFalse(Files.exists(legacyJsonl), "迁移后旧自定义目录中的 JSONL 应移除");
    }

    @Test
    void moveJsonlFilesShouldSkipExistingTargetWithoutOverwrite() throws Exception {
        Path source = tempDir.resolve("src");
        Path target = tempDir.resolve("tgt");
        Files.createDirectories(source);
        Files.createDirectories(target);
        Path sourceFile = source.resolve("1700000000000-x.jsonl");
        Files.writeString(sourceFile, "new content", StandardCharsets.UTF_8);
        Path existing = target.resolve("1700000000000-x.jsonl");
        Files.writeString(existing, "existing content", StandardCharsets.UTF_8);

        Method moveMethod = InfluxDbProvider.class.getDeclaredMethod("moveJsonlFiles", Path.class, Path.class);
        moveMethod.setAccessible(true);
        int moved = (Integer) moveMethod.invoke(provider, source, target);
        Assertions.assertEquals(0, moved, "目标已存在同名时不能覆盖，moveJsonlFiles 必须跳过");
        Assertions.assertEquals("existing content", Files.readString(existing, StandardCharsets.UTF_8),
                "目标文件内容不能被覆盖");
        Assertions.assertTrue(Files.exists(sourceFile),
                "源文件在跳过的情况下应保留，等待用户手工处理");
    }

    private Object invoke(String methodName) {
        return invoke(methodName, new Class[]{});
    }

    private Object invoke(String methodName, Class<?>[] paramTypes, Object... args) {
        try {
            Method m = InfluxDbProvider.class.getDeclaredMethod(methodName, paramTypes);
            m.setAccessible(true);
            return m.invoke(provider, args);
        } catch (Exception e) {
            throw new RuntimeException("反射调用 " + methodName + " 失败", e);
        }
    }

    private RuntimeDetailVO sampleVo() {
        RuntimeDetailVO vo = new RuntimeDetailVO();
        ReflectionTestUtils.setField(vo, "timestamp", 1_700_000_000_000L);
        ReflectionTestUtils.setField(vo, "cpuUsage", 0.42);
        ReflectionTestUtils.setField(vo, "memoryUsage", 8.0);
        ReflectionTestUtils.setField(vo, "diskUsage", 100.0);
        ReflectionTestUtils.setField(vo, "networkUpload", 12.5);
        ReflectionTestUtils.setField(vo, "networkDownload", 25.0);
        ReflectionTestUtils.setField(vo, "diskRead", 1.0);
        ReflectionTestUtils.setField(vo, "diskWrite", 2.0);
        return vo;
    }

    private static ObjectProvider<TimeSeriesAdapter> objectProvider(TimeSeriesAdapter adapter) {
        return new ObjectProvider<>() {
            @Override
            public TimeSeriesAdapter getObject(Object... args) {
                return adapter;
            }

            @Override
            public TimeSeriesAdapter getIfAvailable() {
                return adapter;
            }

            @Override
            public TimeSeriesAdapter getIfUnique() {
                return adapter;
            }

            @Override
            public TimeSeriesAdapter getObject() {
                return adapter;
            }

            @Override
            public Iterator<TimeSeriesAdapter> iterator() {
                return List.of(adapter).iterator();
            }

            @Override
            public Stream<TimeSeriesAdapter> stream() {
                return Stream.of(adapter);
            }

            @Override
            public Stream<TimeSeriesAdapter> orderedStream() {
                return Stream.of(adapter);
            }
        };
    }

    private static class RecordingAdapter implements TimeSeriesAdapter {
        private int writeCount;
        private int lastClientId;
        private RuntimeDetailVO lastRuntime;

        @Override
        public void writeRuntime(int clientId, RuntimeDetailVO vo) {
            writeCount++;
            lastClientId = clientId;
            lastRuntime = vo;
        }

        @Override
        public void writeOtlpMetric(int clientId, RuntimeDetailVO vo) {
            writeRuntime(clientId, vo);
        }

        @Override
        public RuntimeHistoryVO readRuntimeHistory(int clientId, java.time.Instant from, java.time.Instant to) {
            return new RuntimeHistoryVO();
        }

        @Override
        public double[] readAvailabilityBuckets(int clientId) {
            return new double[0];
        }
    }
}
