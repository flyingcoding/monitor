package com.example.tsdb;

import com.example.entity.vo.response.RuntimeHistoryVO;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link InfluxDbProvider} 历史查询 Flux 生成测试。
 *
 * <p>用 JDK 动态代理捕获 {@link QueryApi#query(String, String)} 入参，不连接真实 InfluxDB。
 */
class InfluxDbProviderHistoryTest {

    private InfluxDbProvider provider;
    private final AtomicReference<String> capturedQuery = new AtomicReference<>();
    private final AtomicReference<List<FluxTable>> queryResult = new AtomicReference<>(List.of());

    @BeforeEach
    void setUp() {
        capturedQuery.set(null);
        queryResult.set(List.of());
        provider = new InfluxDbProvider();
        ReflectionTestUtils.setField(provider, "bucket", "program");
        ReflectionTestUtils.setField(provider, "organization", "flyingcoding");
        ReflectionTestUtils.setField(provider, "client", clientProxy(queryApiProxy()));
    }

    /**
     * 1h 内窗口应保留原始分辨率，不追加 aggregateWindow，避免旧默认 1h 语义被桶化改写。
     */
    @Test
    void readRuntimeHistoryShouldNotAggregateNativeOneHourWindow() {
        Instant from = Instant.parse("2026-05-20T00:00:00Z");
        provider.readRuntimeHistory(42, from, from.plusSeconds(3600));

        String query = capturedQuery.get();
        Assertions.assertNotNull(query);
        Assertions.assertFalse(query.contains("aggregateWindow"),
                "≤1h 原始分辨率查询不应追加 aggregateWindow");
        Assertions.assertTrue(query.contains("|> range(start: 2026-05-20T00:00:00Z, stop: 2026-05-20T01:00:00Z)"));
    }

    /**
     * 超过 1h 的窗口仍需按 step 做服务端均值聚合，控制返回点数。
     */
    @Test
    void readRuntimeHistoryShouldAggregateLongWindow() {
        Instant from = Instant.parse("2026-05-20T00:00:00Z");
        provider.readRuntimeHistory(42, from, from.plusSeconds(3601));

        String query = capturedQuery.get();
        Assertions.assertNotNull(query);
        Assertions.assertTrue(query.contains("|> aggregateWindow(every: 30s, fn: mean, createEmpty: false)"),
                "1h+1s 应进入 30s 聚合窗口");
    }

    /**
     * InfluxDB 每个 field 会作为独立 table 返回；aggregateWindow(createEmpty:false) 后可选字段可能稀疏。
     * 读取历史时必须按 record time 合并，不能假设所有 table 具有相同下标和长度。
     */
    @Test
    void readRuntimeHistoryShouldMergeSparseTablesByTimestamp() {
        Instant t0 = Instant.parse("2026-05-20T00:00:00Z");
        Instant t1 = t0.plusSeconds(30);
        queryResult.set(List.of(
                table(record(0, t0, "cpuUsage", 0.1), record(0, t1, "cpuUsage", 0.2)),
                table(record(1, t1, "gpuTemperatureMax", 66.0))
        ));

        RuntimeHistoryVO vo = provider.readRuntimeHistory(42, t0, t1.plusSeconds(30));

        Assertions.assertEquals(2, vo.getList().size(), "两个不同时间戳应输出两行");
        Assertions.assertEquals(t0, vo.getList().get(0).get("timestamp"));
        Assertions.assertEquals(0.1, ((Number) vo.getList().get(0).get("cpuUsage")).doubleValue(), 1e-9);
        Assertions.assertNull(vo.getList().get(0).get("gpuTemperatureMax"), "稀疏可选字段不能错配到旧时间桶");
        Assertions.assertEquals(t1, vo.getList().get(1).get("timestamp"));
        Assertions.assertEquals(0.2, ((Number) vo.getList().get(1).get("cpuUsage")).doubleValue(), 1e-9);
        Assertions.assertEquals(66.0, ((Number) vo.getList().get(1).get("gpuTemperatureMax")).doubleValue(), 1e-9);
    }

    private QueryApi queryApiProxy() {
        return (QueryApi) Proxy.newProxyInstance(
                QueryApi.class.getClassLoader(),
                new Class[]{QueryApi.class},
                (proxy, method, args) -> {
                    if ("query".equals(method.getName())
                            && args != null
                            && args.length == 2
                            && args[0] instanceof String query) {
                        capturedQuery.set(query);
                        return queryResult.get();
                    }
                    return defaultValue(method.getReturnType(), proxy);
                });
    }

    /**
     * 构造一个仅包含指定记录的 Flux table，用于模拟 InfluxDB 按 field 拆表返回。
     */
    private FluxTable table(FluxRecord... records) {
        FluxTable table = new FluxTable();
        table.getRecords().addAll(List.of(records));
        return table;
    }

    /**
     * 构造历史查询结果中的单条 Flux record，填充生产代码读取的 _time / _field / _value 三列。
     */
    private FluxRecord record(int table, Instant time, String field, Object value) {
        FluxRecord record = new FluxRecord(table);
        record.getValues().put("_time", time);
        record.getValues().put("_field", field);
        record.getValues().put("_value", value);
        return record;
    }

    private InfluxDBClient clientProxy(QueryApi queryApi) {
        return (InfluxDBClient) Proxy.newProxyInstance(
                InfluxDBClient.class.getClassLoader(),
                new Class[]{InfluxDBClient.class},
                (proxy, method, args) -> {
                    if ("getQueryApi".equals(method.getName())) {
                        return queryApi;
                    }
                    return defaultValue(method.getReturnType(), proxy);
                });
    }

    private Object defaultValue(Class<?> returnType, Object proxy) {
        if (returnType == Void.TYPE) return null;
        if (returnType == Boolean.TYPE) return false;
        if (returnType == Integer.TYPE) return 0;
        if (returnType == Long.TYPE) return 0L;
        if (returnType == Double.TYPE) return 0.0d;
        if (returnType == Float.TYPE) return 0.0f;
        if (returnType == String.class) return "InfluxDbProviderHistoryTestProxy";
        if (returnType.isInstance(proxy)) return proxy;
        return null;
    }
}
