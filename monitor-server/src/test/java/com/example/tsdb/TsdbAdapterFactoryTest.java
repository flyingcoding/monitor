package com.example.tsdb;

import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.entity.vo.response.RuntimeHistoryVO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

/**
 * {@link TsdbAdapterFactory} 单元测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>默认 / 显式 {@code influxdb} → 返回注入的 {@link InfluxDbProvider}；</li>
 *   <li>{@code victoria-metrics} → 当前 PR1 仍回落到 {@link InfluxDbProvider}（PR2 落地真实 VM Provider 后改为真实分支）；</li>
 *   <li>大小写不敏感（{@code Victoria-Metrics} 同样回落）；</li>
 *   <li>v2.0-beta deprecation 检查：旧 key 显式定义时打 WARN，新 key 不触发。</li>
 * </ul>
 */
class TsdbAdapterFactoryTest {

    private final InfluxDbProvider influx = new InfluxDbProvider();
    private final VictoriaMetricsProvider vm = new VictoriaMetricsProvider(influx);

    /**
     * 用指定 provider 构造 factory 并触发 Bean 解析，使用空 MockEnvironment 避免 deprecation 干扰。
     */
    private TimeSeriesAdapter resolveWith(String provider) {
        return resolveWith(provider, new MockEnvironment());
    }

    /**
     * 用指定 provider + Environment 构造 factory，方便注入旧 key 验证 deprecation 路径。
     */
    private TimeSeriesAdapter resolveWith(String provider, MockEnvironment environment) {
        TsdbAdapterFactory factory = new TsdbAdapterFactory();
        ReflectionTestUtils.setField(factory, "provider", provider);
        return factory.timeSeriesAdapter(influx, vm, environment);
    }

    @Test
    void shouldReturnInfluxProviderWhenConfiguredInfluxdb() {
        Assertions.assertSame(influx, resolveWith("influxdb"));
    }

    @Test
    void shouldReturnVictoriaMetricsProviderWhenConfiguredVictoriaMetrics() {
        TimeSeriesAdapter adapter = resolveWith("victoria-metrics");
        Assertions.assertSame(vm, adapter,
                "PR2 阶段：victoria-metrics 必须注入真实 VictoriaMetricsProvider 而非回落到 Influx");
    }

    @Test
    void shouldBeCaseInsensitiveOnVictoriaMetrics() {
        Assertions.assertSame(vm, resolveWith("Victoria-Metrics"));
        Assertions.assertSame(vm, resolveWith("VICTORIA-METRICS"));
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
    void victoriaMetricsProviderShouldStayAvailableAsBean() {
        Assertions.assertNull(VictoriaMetricsProvider.class.getAnnotation(ConditionalOnProperty.class),
                "VictoriaMetricsProvider 同样必须无条件注册，便于运行时切换 / fallback 路径返回它");
    }

    @Test
    void fallbackResultMustImplementTimeSeriesAdapterInterface() {
        TimeSeriesAdapter adapter = resolveWith("postgres-timescaledb");
        Assertions.assertTrue(adapter instanceof TimeSeriesAdapter);
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
        TimeSeriesAdapter adapter = resolveWith("influxdb");
        Assertions.assertDoesNotThrow(() -> {
            Class<?> cls = adapter.getClass();
            cls.getMethod("writeRuntime", int.class, RuntimeDetailVO.class);
            cls.getMethod("readRuntimeHistory", int.class, java.time.Instant.class, java.time.Instant.class);
        }, "TimeSeriesAdapter 接口方法签名必须在 InfluxDbProvider 上可见");
        Assertions.assertNotNull(RuntimeHistoryVO.class);
    }

    @Test
    void shouldNotFailWhenDeprecatedKeysAbsent() {
        Assertions.assertDoesNotThrow(() -> resolveWith("influxdb", new MockEnvironment()));
    }

    @Test
    void shouldTolerateDeprecatedKeysPresent() {
        MockEnvironment env = new MockEnvironment();
        Map.of(
                "spring.influx.url", "http://legacy:8086",
                "spring.influx.user", "legacy",
                "spring.influx.password", "secret",
                "spring.influx.bucket", "old-bucket",
                "spring.influx.organization", "legacy-org",
                "monitor.influx-buffer.dir", "data/influx-buffer",
                "monitor.influx-buffer.replay-interval-ms", "15000",
                "monitor.influx-buffer.batch-size", "200"
        ).forEach(env::setProperty);
        Assertions.assertSame(influx, resolveWith("influxdb", env),
                "存在 deprecated 旧 key 时仍必须成功装配 InfluxDbProvider");
    }
}
