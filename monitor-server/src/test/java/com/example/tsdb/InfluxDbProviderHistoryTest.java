package com.example.tsdb;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.QueryApi;
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

    @BeforeEach
    void setUp() {
        capturedQuery.set(null);
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
                        return List.of();
                    }
                    return defaultValue(method.getReturnType(), proxy);
                });
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
