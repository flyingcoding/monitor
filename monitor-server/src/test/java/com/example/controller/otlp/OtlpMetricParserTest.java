package com.example.controller.otlp;

import com.example.entity.vo.request.RuntimeDetailVO;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.Gauge;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.metrics.v1.Sum;
import io.opentelemetry.proto.resource.v1.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * {@link OtlpMetricParser} 单元测试。
 *
 * <p>覆盖:
 * <ul>
 *   <li>白名单 metric 完整映射 (cpuUsage / memoryUsage / networkUpload 等);</li>
 *   <li>host.name resource attribute 提取;</li>
 *   <li>未识别 metric 计数累加;</li>
 *   <li>Gauge int / double 值都能解析;</li>
 *   <li>Sum 类型 metric 被跳过 (D3: alpha 仅 Gauge);</li>
 *   <li>多 ResourceMetrics 解析为多个 Result;</li>
 *   <li>空请求 / null 安全。</li>
 * </ul>
 */
class OtlpMetricParserTest {

    @Test
    void shouldMapAllWhitelistedMetrics() {
        long fixedMs = 1_700_000_000_000L;
        long fixedNs = fixedMs * 1_000_000L;
        ExportMetricsServiceRequest req = ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(ResourceMetrics.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(stringAttr("host.name", "host-01")))
                        .addScopeMetrics(ScopeMetrics.newBuilder()
                                .addMetrics(gauge("monitor.client.cpu_usage", 0.55, fixedNs))
                                .addMetrics(gauge("monitor.client.memory_used_gb", 8.5, fixedNs))
                                .addMetrics(gauge("monitor.client.disk_used_gb", 120.0, fixedNs))
                                .addMetrics(gauge("monitor.client.network_upload_kbps", 12.5, fixedNs))
                                .addMetrics(gauge("monitor.client.network_download_kbps", 25.0, fixedNs))
                                .addMetrics(gauge("monitor.client.disk_read_mbps", 1.5, fixedNs))
                                .addMetrics(gauge("monitor.client.disk_write_mbps", 2.5, fixedNs))
                                .addMetrics(gaugeInt("monitor.client.smart_critical_count", 3, fixedNs))
                                .addMetrics(gaugeInt("monitor.client.systemd_failed_count", 1, fixedNs))
                                .addMetrics(gauge("monitor.client.gpu_temperature_max", 78.0, fixedNs))
                                .addMetrics(gaugeInt("monitor.client.watched_process_missing", 2, fixedNs))))
                .build();

        List<OtlpMetricParser.Result> results = OtlpMetricParser.parse(req);
        Assertions.assertEquals(1, results.size());
        OtlpMetricParser.Result r = results.get(0);
        Assertions.assertEquals("host-01", r.getHostName());
        RuntimeDetailVO vo = r.getRuntime();
        Assertions.assertEquals(0.55, vo.getCpuUsage(), 1e-9);
        Assertions.assertEquals(8.5, vo.getMemoryUsage(), 1e-9);
        Assertions.assertEquals(120.0, vo.getDiskUsage(), 1e-9);
        Assertions.assertEquals(12.5, vo.getNetworkUpload(), 1e-9);
        Assertions.assertEquals(25.0, vo.getNetworkDownload(), 1e-9);
        Assertions.assertEquals(1.5, vo.getDiskRead(), 1e-9);
        Assertions.assertEquals(2.5, vo.getDiskWrite(), 1e-9);
        Assertions.assertEquals(78.0, vo.getGpuTemperatureMax());
        Assertions.assertEquals(3, vo.getSmartCriticalCount());
        Assertions.assertEquals(1, vo.getSystemdFailedCount());
        Assertions.assertEquals(2, vo.getWatchedProcessMissing());
        Assertions.assertEquals(fixedMs, vo.getTimestamp());
        Assertions.assertTrue(r.getUnknownMetricCounts().isEmpty());
    }

    @Test
    void shouldCountUnknownMetricsWithoutFailing() {
        ExportMetricsServiceRequest req = ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(ResourceMetrics.newBuilder()
                        .addScopeMetrics(ScopeMetrics.newBuilder()
                                .addMetrics(gauge("monitor.client.cpu_usage", 0.1, nowNs()))
                                .addMetrics(gauge("system.cpu.utilization", 0.99, nowNs()))
                                .addMetrics(gauge("foo.bar.baz", 1.0, nowNs()))
                                .addMetrics(gauge("foo.bar.baz", 2.0, nowNs()))))
                .build();

        List<OtlpMetricParser.Result> results = OtlpMetricParser.parse(req);
        Assertions.assertEquals(1, results.size());
        OtlpMetricParser.Result r = results.get(0);
        Assertions.assertEquals(0.1, r.getRuntime().getCpuUsage(), 1e-9);
        Assertions.assertEquals(2, r.getUnknownMetricCounts().size());
        Assertions.assertEquals(1L, r.getUnknownMetricCounts().get("system.cpu.utilization"));
        Assertions.assertEquals(2L, r.getUnknownMetricCounts().get("foo.bar.baz"));
    }

    @Test
    void shouldSkipSumTypeMetrics() {
        ExportMetricsServiceRequest req = ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(ResourceMetrics.newBuilder()
                        .addScopeMetrics(ScopeMetrics.newBuilder()
                                .addMetrics(Metric.newBuilder()
                                        .setName("monitor.client.cpu_usage")
                                        .setSum(Sum.newBuilder()
                                                .addDataPoints(NumberDataPoint.newBuilder()
                                                        .setTimeUnixNano(nowNs())
                                                        .setAsDouble(0.5))))))
                .build();
        List<OtlpMetricParser.Result> results = OtlpMetricParser.parse(req);
        // 没有 Gauge 数据点 → parseResource 不会增量 hasAnyMetric → 因为未知列表也为空 → 返回 null
        Assertions.assertTrue(results.isEmpty(),
                "Sum 类型即使 metric 名在白名单中也应被跳过（v2.0-alpha 决策 D3）");
    }

    @Test
    void shouldHandleMultipleResourceMetricsBlocks() {
        ExportMetricsServiceRequest req = ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(ResourceMetrics.newBuilder()
                        .setResource(Resource.newBuilder().addAttributes(stringAttr("host.name", "h1")))
                        .addScopeMetrics(ScopeMetrics.newBuilder()
                                .addMetrics(gauge("monitor.client.cpu_usage", 0.1, nowNs()))))
                .addResourceMetrics(ResourceMetrics.newBuilder()
                        .setResource(Resource.newBuilder().addAttributes(stringAttr("host.name", "h2")))
                        .addScopeMetrics(ScopeMetrics.newBuilder()
                                .addMetrics(gauge("monitor.client.cpu_usage", 0.2, nowNs()))))
                .build();
        List<OtlpMetricParser.Result> results = OtlpMetricParser.parse(req);
        Assertions.assertEquals(2, results.size());
        Assertions.assertEquals("h1", results.get(0).getHostName());
        Assertions.assertEquals("h2", results.get(1).getHostName());
    }

    @Test
    void shouldFillTimestampWhenAbsent() {
        ExportMetricsServiceRequest req = ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(ResourceMetrics.newBuilder()
                        .addScopeMetrics(ScopeMetrics.newBuilder()
                                .addMetrics(gauge("monitor.client.cpu_usage", 0.5, 0L))))
                .build();
        long before = System.currentTimeMillis();
        List<OtlpMetricParser.Result> results = OtlpMetricParser.parse(req);
        long after = System.currentTimeMillis();
        Assertions.assertEquals(1, results.size());
        long ts = results.get(0).getRuntime().getTimestamp();
        Assertions.assertTrue(ts >= before && ts <= after,
                "数据点没有 timestamp 时应回填为当前时间，ts=" + ts);
    }

    @Test
    void shouldBeSafeOnNullRequest() {
        List<OtlpMetricParser.Result> results = OtlpMetricParser.parse(null);
        Assertions.assertTrue(results.isEmpty());
    }

    @Test
    void shouldBeSafeOnEmptyRequest() {
        List<OtlpMetricParser.Result> results =
                OtlpMetricParser.parse(ExportMetricsServiceRequest.newBuilder().build());
        Assertions.assertTrue(results.isEmpty());
    }

    @Test
    void hostNameShouldBeNullWhenResourceMissing() {
        ExportMetricsServiceRequest req = ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(ResourceMetrics.newBuilder()
                        .addScopeMetrics(ScopeMetrics.newBuilder()
                                .addMetrics(gauge("monitor.client.cpu_usage", 0.3, nowNs()))))
                .build();
        List<OtlpMetricParser.Result> results = OtlpMetricParser.parse(req);
        Assertions.assertEquals(1, results.size());
        Assertions.assertNull(results.get(0).getHostName());
    }

    private static long nowNs() {
        return System.currentTimeMillis() * 1_000_000L;
    }

    private static KeyValue stringAttr(String key, String value) {
        return KeyValue.newBuilder()
                .setKey(key)
                .setValue(AnyValue.newBuilder().setStringValue(value))
                .build();
    }

    private static Metric gauge(String name, double value, long timeUnixNano) {
        return Metric.newBuilder()
                .setName(name)
                .setGauge(Gauge.newBuilder()
                        .addDataPoints(NumberDataPoint.newBuilder()
                                .setTimeUnixNano(timeUnixNano)
                                .setAsDouble(value)))
                .build();
    }

    private static Metric gaugeInt(String name, long value, long timeUnixNano) {
        return Metric.newBuilder()
                .setName(name)
                .setGauge(Gauge.newBuilder()
                        .addDataPoints(NumberDataPoint.newBuilder()
                                .setTimeUnixNano(timeUnixNano)
                                .setAsInt(value)))
                .build();
    }
}
