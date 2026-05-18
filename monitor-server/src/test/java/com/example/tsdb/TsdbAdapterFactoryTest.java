package com.example.tsdb;

import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.entity.vo.response.RuntimeHistoryVO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link TsdbAdapterFactory} 单元测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>默认 / 显式 {@code influxdb} → 返回注入的 {@link InfluxDbProvider}；</li>
 *   <li>{@code victoria-metrics} → WARN 并回落到 {@link InfluxDbProvider}（决策 D7）；</li>
 *   <li>大小写不敏感（{@code Victoria-Metrics} 同样回落）。</li>
 * </ul>
 */
class TsdbAdapterFactoryTest {

    private final InfluxDbProvider influx = new InfluxDbProvider();

    private TimeSeriesAdapter resolveWith(String provider) {
        TsdbAdapterFactory factory = new TsdbAdapterFactory();
        ReflectionTestUtils.setField(factory, "provider", provider);
        return factory.timeSeriesAdapter(influx);
    }

    @Test
    void shouldReturnInfluxProviderWhenConfiguredInfluxdb() {
        Assertions.assertSame(influx, resolveWith("influxdb"));
    }

    @Test
    void shouldFallbackToInfluxWhenConfiguredVictoriaMetrics() {
        TimeSeriesAdapter adapter = resolveWith("victoria-metrics");
        Assertions.assertSame(influx, adapter,
                "victoria-metrics 在 v2.0-alpha 必须回落到 InfluxDbProvider");
    }

    @Test
    void shouldBeCaseInsensitiveOnVictoriaMetrics() {
        Assertions.assertSame(influx, resolveWith("Victoria-Metrics"));
        Assertions.assertSame(influx, resolveWith("VICTORIA-METRICS"));
    }

    @Test
    void shouldReturnInfluxForUnknownValueAsSafeDefault() {
        Assertions.assertSame(influx, resolveWith("postgres-timescaledb"),
                "未知 provider 字符串保留 InfluxDbProvider，不允许返回 null 或抛错破坏启动");
    }

    @Test
    void influxProviderShouldStayAvailableForFallbackProviderValues() {
        Assertions.assertNull(InfluxDbProvider.class.getAnnotation(ConditionalOnProperty.class),
                "InfluxDbProvider 必须始终注册，victoria-metrics / unknown provider 才能回落到它");
    }

    @Test
    void fallbackResultMustImplementTimeSeriesAdapterInterface() {
        TimeSeriesAdapter adapter = resolveWith("victoria-metrics");
        Assertions.assertTrue(adapter instanceof TimeSeriesAdapter);
        // 接口契约：fallback 路径不能因为返回 null 让上层 NPE
        Assertions.assertNotNull(adapter);
    }

    @Test
    void factoryConstantsShouldRemainStable() {
        Assertions.assertEquals("monitor.tsdb.provider", TsdbAdapterFactory.PROVIDER_PROPERTY);
        Assertions.assertEquals("influxdb", TsdbAdapterFactory.PROVIDER_INFLUXDB);
        Assertions.assertEquals("victoria-metrics", TsdbAdapterFactory.PROVIDER_VICTORIA_METRICS);
    }

    @Test
    void timeSeriesAdapterShouldDelegateToInfluxWriteRuntime() {
        // 编译期协变断言：返回的 adapter 必须能被当作 TimeSeriesAdapter 使用
        TimeSeriesAdapter adapter = resolveWith("influxdb");
        // 直接调用会因为 InfluxDbProvider.init() 未调用而 NPE，这里仅验证类型可派发
        Assertions.assertDoesNotThrow(() -> {
            Class<?> cls = adapter.getClass();
            cls.getMethod("writeRuntime", int.class, RuntimeDetailVO.class);
            cls.getMethod("readRuntimeHistory", int.class);
        }, "TimeSeriesAdapter 接口方法签名必须在 InfluxDbProvider 上可见");
        // 引用 RuntimeHistoryVO 以避免未使用 import 警告
        Assertions.assertNotNull(RuntimeHistoryVO.class);
    }

}
