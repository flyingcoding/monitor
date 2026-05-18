package com.example.tsdb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 时序后端 Bean 工厂 (v2.0-alpha)。
 *
 * <p>根据配置 {@code monitor.tsdb.provider} 选择当前生效的 {@link TimeSeriesAdapter}：
 *
 * <ul>
 *   <li>{@code influxdb}（默认 / 未配置）—— 直接使用 {@link InfluxDbProvider}。</li>
 *   <li>{@code victoria-metrics} —— v2.0-alpha 未实装，工厂打印 WARN 后注入 {@link InfluxDbProvider}
 *       作为回落实现（决策 D7：fallback 优先于 fail-fast）。</li>
 * </ul>
 *
 * <p>这是 v2.0-alpha 的"骨架"——v2.0-beta 真上 {@code VictoriaMetricsProvider} 时，仅需把下面
 * fallback 分支替换为构造 VM 客户端并返回它。
 */
@Slf4j
@Configuration
public class TsdbAdapterFactory {

    /** 配置 key：{@code monitor.tsdb.provider}。 */
    public static final String PROVIDER_PROPERTY = "monitor.tsdb.provider";

    /** 默认 / InfluxDB 选项值。 */
    public static final String PROVIDER_INFLUXDB = "influxdb";

    /** VictoriaMetrics 选项值（alpha 未实装）。 */
    public static final String PROVIDER_VICTORIA_METRICS = "victoria-metrics";

    @Value("${" + PROVIDER_PROPERTY + ":" + PROVIDER_INFLUXDB + "}")
    private String provider;

    /**
     * 注入一个标记为 {@link Primary} 的当前生效 Adapter。
     *
     * <p>v2.0-alpha 的所有 provider 值都返回 {@link InfluxDbProvider}；当配置为
     * {@code victoria-metrics} 时额外打印 WARN，显式说明当前是回落路径。
     *
     * @param influxDbProvider 真实的 {@link InfluxDbProvider} Bean（由 Spring 注入）
     * @return 当前生效的时序适配器
     */
    @Bean
    @Primary
    public TimeSeriesAdapter timeSeriesAdapter(InfluxDbProvider influxDbProvider) {
        if (PROVIDER_VICTORIA_METRICS.equalsIgnoreCase(provider)) {
            log.warn("monitor.tsdb.provider=victoria-metrics 在 v2.0-alpha 未实现，自动回落到 InfluxDbProvider；如需继续观望该路径请等待 v2.0-beta");
            return influxDbProvider;
        }
        return influxDbProvider;
    }
}
