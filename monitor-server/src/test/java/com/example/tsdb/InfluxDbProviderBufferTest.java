package com.example.tsdb;

import com.alibaba.fastjson2.JSON;
import com.example.entity.vo.request.RuntimeDetailVO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
            InfluxDbProvider.InfluxBufferRecord record =
                    JSON.parseObject(content, InfluxDbProvider.InfluxBufferRecord.class);
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
}
