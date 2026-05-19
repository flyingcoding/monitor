package com.example.tsdb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 时序后端 Bean 工厂 (v2.0-alpha → v2.0-beta)。
 *
 * <p>根据配置 {@code monitor.tsdb.provider} 选择当前生效的 {@link TimeSeriesAdapter}：
 *
 * <ul>
 *   <li>{@code influxdb}（默认 / 未配置）—— 直接使用 {@link InfluxDbProvider}。</li>
 *   <li>{@code victoria-metrics} —— 注入真实的 {@link VictoriaMetricsProvider}（v2.0-beta 落地）。</li>
 *   <li>未知 provider 字符串 —— WARN 并回落 {@link InfluxDbProvider}（始终允许部署成功，不 fail-fast）。</li>
 * </ul>
 *
 * <h3>v2.0-beta deprecation 检查</h3>
 * <p>启动时（Bean 创建期，{@link #timeSeriesAdapter} 第一次调用）一次性扫描 Environment：
 * 若检测到 v1.x / v2.0-alpha 的旧配置 key（{@code spring.influx.*} / {@code monitor.influx-buffer.*}）
 * 仍被显式定义，打 WARN 提示用户迁移到新 namespace {@code monitor.tsdb.{influxdb,buffer}.*}，
 * 旧 key 计划在 v2.1.x 移除。
 */
@Slf4j
@Configuration
public class TsdbAdapterFactory {

    /** 配置 key：{@code monitor.tsdb.provider}。 */
    public static final String PROVIDER_PROPERTY = "monitor.tsdb.provider";

    /** 默认 / InfluxDB 选项值。 */
    public static final String PROVIDER_INFLUXDB = "influxdb";

    /** VictoriaMetrics 选项值。 */
    public static final String PROVIDER_VICTORIA_METRICS = "victoria-metrics";

    /** 旧 key → 新 key 的迁移映射，启动时检测旧 key 是否被显式设置。 */
    private static final Map<String, String> DEPRECATED_KEY_MIGRATIONS = buildDeprecatedKeyMigrations();

    @Value("${" + PROVIDER_PROPERTY + ":" + PROVIDER_INFLUXDB + "}")
    private String provider;

    /**
     * 构造旧配置 key 到新 namespace 的迁移指引；按业务相关性排序，便于 WARN 输出可读。
     *
     * @return 不可变 map，key=v1.x/alpha 旧路径，value=v2.0-beta 新路径
     */
    private static Map<String, String> buildDeprecatedKeyMigrations() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("spring.influx.url", "monitor.tsdb.influxdb.url");
        map.put("spring.influx.user", "monitor.tsdb.influxdb.user");
        map.put("spring.influx.password", "monitor.tsdb.influxdb.password");
        map.put("spring.influx.bucket", "monitor.tsdb.influxdb.bucket");
        map.put("spring.influx.organization", "monitor.tsdb.influxdb.organization");
        map.put("monitor.influx-buffer.dir", "monitor.tsdb.buffer.dir");
        map.put("monitor.influx-buffer.replay-interval-ms", "monitor.tsdb.buffer.replay-interval-ms");
        map.put("monitor.influx-buffer.batch-size", "monitor.tsdb.buffer.batch-size");
        return map;
    }

    /**
     * 注入一个标记为 {@link Primary} 的当前生效 Adapter。
     *
     * <p>PR2（v2.0-beta）：{@code monitor.tsdb.provider=victoria-metrics} 时注入真实的
     * {@link VictoriaMetricsProvider} Bean；{@code influxdb} 或未知值仍注入
     * {@link InfluxDbProvider}（保持 spec database-guidelines §3：未知 provider 字符串回落到
     * InfluxDbProvider，且 InfluxDbProvider 必须始终可作为 fallback 主体可用）。
     *
     * @param influxDbProvider 真实的 {@link InfluxDbProvider} Bean（Spring 注入，始终注册）
     * @param victoriaMetricsProvider 真实的 {@link VictoriaMetricsProvider} Bean（Spring 注入，始终注册）
     * @param environment      Spring Environment，用于检测 deprecated 配置 key
     * @return 当前生效的时序适配器
     */
    @Bean
    @Primary
    public TimeSeriesAdapter timeSeriesAdapter(
            InfluxDbProvider influxDbProvider,
            VictoriaMetricsProvider victoriaMetricsProvider,
            Environment environment) {
        this.warnDeprecatedConfigKeys(environment);
        if (PROVIDER_VICTORIA_METRICS.equalsIgnoreCase(provider)) {
            log.info("monitor.tsdb.provider=victoria-metrics → 注入 VictoriaMetricsProvider 作为主时序后端");
            return victoriaMetricsProvider;
        }
        if (!PROVIDER_INFLUXDB.equalsIgnoreCase(provider)) {
            log.warn("monitor.tsdb.provider={} 未识别，回落到 InfluxDbProvider", provider);
        }
        return influxDbProvider;
    }

    /**
     * 扫描 Environment，对仍显式定义的 v1.x / v2.0-alpha 旧 key 打 WARN 提示迁移路径。
     *
     * <p>检测方式：使用 {@link Environment#containsProperty(String)}，仅当用户显式配置（yml / env）
     * 时触发；嵌套占位符默认值（如 {@code @Value("${new.key:${old.key:}}")} 内出现的 {@code old.key}）
     * 不会被误报，因为占位符默认值不会注册到 PropertySources。
     *
     * @param environment Spring Environment
     */
    private void warnDeprecatedConfigKeys(Environment environment) {
        for (Map.Entry<String, String> entry : DEPRECATED_KEY_MIGRATIONS.entrySet()) {
            String oldKey = entry.getKey();
            String newKey = entry.getValue();
            if (environment.containsProperty(oldKey)) {
                log.warn("配置 key {} 在 v2.0-beta 已 deprecated，请迁移到 {}（计划 v2.1.x 移除）",
                        oldKey, newKey);
            }
        }
    }
}
