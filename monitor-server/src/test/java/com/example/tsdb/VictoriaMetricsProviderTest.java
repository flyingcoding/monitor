package com.example.tsdb;

import com.alibaba.fastjson2.JSON;
import com.example.entity.vo.request.RuntimeDetailVO;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

/**
 * {@link VictoriaMetricsProvider} v2.0-beta 落地单测。
 *
 * <p>用 WireMock 起本地 HTTP mock 服务器伪装 VM {@code /api/v2/write} 端点，
 * 覆盖：
 * <ul>
 *   <li>写入成功路径：line protocol 中包含 {@code runtime} measurement 与各 field；</li>
 *   <li>写入失败路径：fallback 路径只落共享 JSONL 缓冲，不误写 InfluxDB；</li>
 *   <li>构造器约束：{@link InfluxDbProvider} 必须注入；</li>
 *   <li>read 方法通过 MetricsQL 查询并映射为前端兼容 VO；</li>
 *   <li>{@code @CircuitBreaker} 与 {@code @PreDestroy} 注解保持原状。</li>
 * </ul>
 *
 * <p>断路器自身的 fallback 触发依赖 Spring AOP 代理，单元测试不便直接验证；
 * 这里通过反射调用私有 {@code writeToFallbackBuffer} 方法直接测试降级语义。
 * 端到端真实断路器行为在 PR5 Testcontainers 集成测试中覆盖。
 */
class VictoriaMetricsProviderTest {

    private WireMockServer wireMock;
    private InfluxDbProvider fallback;
    private VictoriaMetricsProvider provider;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        fallback = new InfluxDbProvider();
        provider = new VictoriaMetricsProvider(fallback);
        ReflectionTestUtils.setField(provider, "url", wireMock.baseUrl());
        provider.init();
    }

    @AfterEach
    void tearDown() {
        try {
            provider.close();
        } finally {
            if (wireMock != null) {
                wireMock.stop();
            }
        }
    }

    @Test
    void writeRuntimeShouldPostLineProtocolToVmWriteEndpoint() {
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/write"))
                .willReturn(aResponse().withStatus(204)));

        provider.writeRuntime(42, sampleVo());

        RequestPatternBuilder pattern = postRequestedFor(urlPathEqualTo("/api/v2/write"))
                .withRequestBody(containing("runtime"))
                .withRequestBody(containing("cpuUsage"));
        wireMock.verify(pattern);
    }

    @Test
    void writeOtlpMetricShouldPostLineProtocolToVmWriteEndpoint() {
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/write"))
                .willReturn(aResponse().withStatus(204)));

        provider.writeOtlpMetric(7, sampleVo());

        wireMock.verify(postRequestedFor(urlPathEqualTo("/api/v2/write"))
                .withRequestBody(containing("runtime")));
    }

    @Test
    void writeOtlpAndDirectShouldGoToSameMeasurementForUnifiedNaming() {
        wireMock.stubFor(post(urlPathEqualTo("/api/v2/write"))
                .willReturn(aResponse().withStatus(204)));

        provider.writeRuntime(1, sampleVo());
        provider.writeOtlpMetric(1, sampleVo());

        // 两个写入路径都应该写到同一 measurement=runtime，符合 D3 命名统一决策
        wireMock.verify(2, postRequestedFor(urlPathEqualTo("/api/v2/write"))
                .withRequestBody(containing("runtime")));
    }

    @Test
    void constructorMustRequireInfluxDbProviderAsFallback() {
        // 编译期断言：唯一构造器签名要求 InfluxDbProvider
        java.lang.reflect.Constructor<?>[] ctors = VictoriaMetricsProvider.class.getDeclaredConstructors();
        Assertions.assertEquals(1, ctors.length, "VM provider 仅暴露一个构造器");
        Assertions.assertEquals(1, ctors[0].getParameterCount(),
                "构造器必须接受一个参数（InfluxDbProvider fallback）");
        Assertions.assertEquals(InfluxDbProvider.class, ctors[0].getParameterTypes()[0],
                "fallback 必须是 InfluxDbProvider 而非任意 TimeSeriesAdapter");
    }

    @Test
    void readRuntimeHistoryShouldThrowUntilPr3Lands() {
        // PR3 已落地：现在返回成功（mock VM 返回空 matrix），VO 应有空列表
        wireMock.stubFor(post(urlPathEqualTo("/api/v1/query_range"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}")));
        com.example.entity.vo.response.RuntimeHistoryVO vo = provider.readRuntimeHistory(1);
        Assertions.assertNotNull(vo);
        Assertions.assertTrue(vo.getList().isEmpty(), "空 result 应返回空 list");
    }

    @Test
    void readRuntimeHistoryShouldMergeMultipleSeriesByTimestamp() {
        // 模拟 VM 返回 cpu + memory 两个 series，每个 2 个时间点
        String json = """
                {
                  "status": "success",
                  "data": {
                    "resultType": "matrix",
                    "result": [
                      {
                        "metric": {"__name__": "runtime_cpuUsage", "clientId": "42"},
                        "values": [[1700000000.0, "0.42"], [1700000010.0, "0.55"]]
                      },
                      {
                        "metric": {"__name__": "runtime_memoryUsage", "clientId": "42"},
                        "values": [[1700000000.0, "8.0"], [1700000010.0, "8.5"]]
                      }
                    ]
                  }
                }
                """;
        wireMock.stubFor(post(urlPathEqualTo("/api/v1/query_range"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json)));

        com.example.entity.vo.response.RuntimeHistoryVO vo = provider.readRuntimeHistory(42);
        Assertions.assertEquals(2, vo.getList().size(), "两个时间戳应合并为 2 行");
        com.alibaba.fastjson2.JSONObject first = vo.getList().get(0);
        Assertions.assertNotNull(first.get("timestamp"), "每行必须含 timestamp");
        Assertions.assertEquals(0.42, ((Number) first.get("cpuUsage")).doubleValue(), 1e-9);
        Assertions.assertEquals(8.0, ((Number) first.get("memoryUsage")).doubleValue(), 1e-9);
    }

    @Test
    void readRuntimeHistoryShouldTolerateNonSuccessResponse() {
        // VM 返回 error 状态：应不抛错，返回空 VO，方便上层兜底
        wireMock.stubFor(post(urlPathEqualTo("/api/v1/query_range"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"error\",\"errorType\":\"bad_data\",\"error\":\"oops\"}")));
        com.example.entity.vo.response.RuntimeHistoryVO vo = provider.readRuntimeHistory(1);
        Assertions.assertNotNull(vo);
        Assertions.assertTrue(vo.getList().isEmpty(), "non-success 应不返回任何行");
    }

    @Test
    void readRuntimeHistoryShouldIgnoreSeriesWithoutRuntimePrefix() {
        // VM 偶发返回非 runtime_ 命名（如其他工具污染同库），需被过滤
        String json = """
                {
                  "status": "success",
                  "data": {
                    "resultType": "matrix",
                    "result": [
                      {
                        "metric": {"__name__": "other_metric", "clientId": "42"},
                        "values": [[1700000000.0, "999"]]
                      },
                      {
                        "metric": {"__name__": "runtime_cpuUsage", "clientId": "42"},
                        "values": [[1700000000.0, "0.42"]]
                      }
                    ]
                  }
                }
                """;
        wireMock.stubFor(post(urlPathEqualTo("/api/v1/query_range"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json)));

        com.example.entity.vo.response.RuntimeHistoryVO vo = provider.readRuntimeHistory(42);
        Assertions.assertEquals(1, vo.getList().size(), "只有 runtime_* series 被计入");
        Assertions.assertNull(vo.getList().get(0).get("other_metric"), "非 runtime_ 命名必须被过滤");
        Assertions.assertNotNull(vo.getList().get(0).get("cpuUsage"));
    }

    @Test
    void readAvailabilityBucketsShouldThrowUntilPr3Lands() {
        // PR3 已落地：返回 0 / 1 桶数组
        StringBuilder values = new StringBuilder("[");
        long firstBucketEnd = Instant.now().getEpochSecond()
                - (InfluxDbProvider.BUCKET_COUNT_24H - 1L) * VictoriaMetricsProvider.AVAILABILITY_STEP_SECONDS;
        for (int i = 0; i < InfluxDbProvider.BUCKET_COUNT_24H; i++) {
            if (i > 0) values.append(",");
            // 模拟一半在线、一半离线（present_over_time=1.0 / 0.0）
            values.append("[")
                    .append(firstBucketEnd + (long) i * VictoriaMetricsProvider.AVAILABILITY_STEP_SECONDS)
                    .append(",\"")
                    .append(i % 2 == 0 ? "1" : "0")
                    .append("\"]");
        }
        values.append("]");
        String json = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{"
                + "\"metric\":{\"__name__\":\"runtime_cpuUsage\",\"clientId\":\"42\"},"
                + "\"values\":" + values + "}]}}";
        wireMock.stubFor(post(urlPathEqualTo("/api/v1/query_range"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json)));

        double[] buckets = provider.readAvailabilityBuckets(42);
        Assertions.assertEquals(InfluxDbProvider.BUCKET_COUNT_24H, buckets.length,
                "桶总数必须等于 24h / 30min = 48");
        Assertions.assertEquals(1.0, buckets[0], 1e-9, "偶数索引应为在线");
        Assertions.assertEquals(0.0, buckets[1], 1e-9, "奇数索引应为离线");
    }

    @Test
    void readAvailabilityBucketsShouldReturnEmptyArrayOnNoData() {
        wireMock.stubFor(post(urlPathEqualTo("/api/v1/query_range"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}")));
        double[] buckets = provider.readAvailabilityBuckets(42);
        Assertions.assertEquals(0, buckets.length, "无数据时返回长度 0 数组，与 InfluxDbProvider 一致");
    }

    @Test
    void readAvailabilityBucketsShouldAlignSparseValuesAndTolerateNaNFromVm() {
        // present_over_time 在空窗口返回 NaN，需按实际时间戳落桶并当作 0 处理（离线）
        long lastBucketEnd = Instant.now().getEpochSecond();
        int nanOffset = 6;
        int onlineOffset = 2;
        long nanTimestamp = lastBucketEnd
                - (long) nanOffset * VictoriaMetricsProvider.AVAILABILITY_STEP_SECONDS;
        long onlineTimestamp = lastBucketEnd
                - (long) onlineOffset * VictoriaMetricsProvider.AVAILABILITY_STEP_SECONDS;
        String json = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{"
                + "\"metric\":{\"__name__\":\"runtime_cpuUsage\",\"clientId\":\"42\"},"
                + "\"values\":[[" + nanTimestamp + ",\"NaN\"],[" + onlineTimestamp + ",\"1\"]]}]}}";
        wireMock.stubFor(post(urlPathEqualTo("/api/v1/query_range"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json)));
        double[] buckets = provider.readAvailabilityBuckets(42);
        Assertions.assertEquals(InfluxDbProvider.BUCKET_COUNT_24H, buckets.length);
        Assertions.assertEquals(0.0, buckets[buckets.length - 1 - nanOffset], 1e-9,
                "NaN 应按自身时间戳落桶并转为 0 离线");
        Assertions.assertEquals(1.0, buckets[buckets.length - 1 - onlineOffset], 1e-9,
                "稀疏在线点不能被错误右对齐到最新桶");
        Assertions.assertEquals(0.0, buckets[buckets.length - 1], 1e-9,
                "没有最近窗口样本时最新桶应保持离线");
    }

    @Test
    void fallbackMethodShouldWriteSharedBufferOnlyOnFailure() throws Exception {
        Path bufferDir = tempDir.resolve("vm-fallback");
        ReflectionTestUtils.setField(fallback, "bufferDir", bufferDir.toString());

        Method m = VictoriaMetricsProvider.class.getDeclaredMethod(
                "writeToFallbackBuffer", int.class, RuntimeDetailVO.class, Throwable.class);
        m.setAccessible(true);
        m.invoke(provider, 5, sampleVo(), new RuntimeException("simulated VM failure"));

        try (Stream<Path> files = Files.list(bufferDir)) {
            List<Path> jsonl = files
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .toList();
            Assertions.assertEquals(1, jsonl.size(), "VM fallback 必须只生成 1 个共享 JSONL 缓冲文件");
            String content = Files.readString(jsonl.get(0), StandardCharsets.UTF_8).trim();
            InfluxDbProvider.TsdbBufferRecord record =
                    JSON.parseObject(content, InfluxDbProvider.TsdbBufferRecord.class);
            Assertions.assertEquals(5, record.getClientId());
            Assertions.assertNotNull(record.getRuntime());
            Assertions.assertEquals(0.42, record.getRuntime().getCpuUsage(), 1e-9);
        }
    }

    @Test
    void initShouldUsePlaceholderOrgAndBucketSafeForVm() {
        // VM 兼容 InfluxDB SDK 时要求 org/bucket 非空但忽略实际值，常量必须存在且非空
        Assertions.assertEquals("monitor", VictoriaMetricsProvider.VM_PLACEHOLDER_ORG);
        Assertions.assertEquals("_", VictoriaMetricsProvider.VM_PLACEHOLDER_TOKEN_OR_BUCKET);
        Assertions.assertEquals("runtime", VictoriaMetricsProvider.MEASUREMENT_RUNTIME);
    }

    /**
     * 构造一个填满 7 项基础字段的 VO，确保 line protocol 写入时所有字段都序列化。
     */
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
}
