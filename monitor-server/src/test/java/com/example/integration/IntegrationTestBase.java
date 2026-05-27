package com.example.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.InfluxDBContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

/**
 * v2.0-tests 集成测试基类：单例 Testcontainers + Spring Boot {@code @ServiceConnection} 自动装配。
 *
 * 容器声明为 {@code static final} 后，JUnit 5 {@code @Testcontainers} 会让所有子类共享同一组容器
 * 实例（singleton container pattern），避免每个测试类启动 30s 容器开销。
 *
 * MySQL / Redis / RabbitMQ 走 {@code @ServiceConnection}（Spring Boot 3.5.10 内置），无需手写
 * {@code @DynamicPropertySource}。InfluxDB 2.7 没有官方 ConnectionDetailsFactory，靠下方
 * {@link #influxProps(DynamicPropertyRegistry)} 显式注入 monitor.tsdb.influxdb.* 与遗留 spring.influx.*
 * 两套 namespace（v2.0-beta 双 namespace 兼容期未结束）。
 *
 * Docker 不可用时 {@code @Testcontainers(disabledWithoutDocker = true)} 让全部子类整组跳过——
 * 配合 Surefire/Failsafe 分层：本地 {@code mvn test} 默认不跑 {@code *IT.java}，CI {@code mvn verify} 跑。
 *
 * 类级 {@code @Sql(executionPhase = AFTER_TEST_METHOD)} 让每个 {@code @Test} 收尾跑
 * {@code cleanup-after-test.sql}：TRUNCATE 业务表 + 复刻 Flyway V1 预置 admin 行。D4 决策：测试数据
 * 在测试代码里 INSERT，不引入 V99__test_seed.sql。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // 集成测试不在 SmokeIT 范围内启动 GreenMail（SMTP 3025），关闭 Spring Boot 自带的
        // MailHealthIndicator，避免 /actuator/health 因 SMTP ping 失败回 503。具体 IT
        // 用到邮件链路时再用 @ImportTestcontainers / @TestConfiguration 注入 GreenMail。
        properties = "management.health.mail.enabled=false"
)
@ActiveProfiles("it")
@Sql(scripts = "/cleanup-after-test.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
public abstract class IntegrationTestBase {

    /**
     * MySQL 容器：使用 tmpfs 挂载 {@code /var/lib/mysql} 让数据文件全部驻留内存——
     * GitHub Actions ubuntu-latest 默认 docker daemon 在 7GB RAM 上跑 4 个容器 + Spring Boot + Maven JVM
     * 时容易因 IO 抖动让 MySQL 健康检查超时被 OOM-killed，进而让后续 IT 的 HikariCP 连接 30s 超时。
     * <p>
     * tmpfs 不持久化（与 IT 数据生命周期"测试方法粒度"语义一致），同时显著降 IO，验证为 PR2 hotfix 的
     * SmokeIT Connection refused 的可能根因之一。
     */
    @Container
    @ServiceConnection
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("monitor")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withReuse(true);

    @Container
    @ServiceConnection(name = "redis")
    protected static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withReuse(true);

    @Container
    @ServiceConnection
    protected static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management-alpine"))
            .withReuse(true);

    /**
     * InfluxDB 2.7 容器初始化：使用 {@code withUsername} / {@code withPassword} 设置 v2 setup 凭证。
     * <p>
     * <b>PR2 hotfix</b>：之前用 {@code withAdmin} / {@code withAdminPassword} 是 v1 字段，
     * 对 v2 setup 流程不起作用——容器实际启动用默认 {@code test-user} / {@code test-password}，
     * 而 IT 注入 {@code monitor.tsdb.influxdb.user=admin} → {@code InfluxDBClientFactory.create}
     * 走的是 v1 兼容鉴权（用户/密码），凭证不匹配 → {@code WriteApiBlocking.writeMeasurement} 抛
     * {@code UnauthorizedException}，最终被 Spring MVC ExceptionHandler 翻成 500。
     * <p>
     * v2 setup 要求 password ≥ 8 字符，故选 {@code monitor-test} 满足约束；
     * organization/bucket 同步对齐到 {@code @DynamicPropertySource} 注入值。
     */
    @Container
    protected static final InfluxDBContainer<?> INFLUX = new InfluxDBContainer<>(DockerImageName.parse("influxdb:2.7"))
            .withUsername("monitor")
            .withPassword("monitor-test-password")
            .withOrganization("monitor")
            .withBucket("monitor")
            .withAdminToken("monitor-it-admin-token")
            .withReuse(true);

    /**
     * InfluxDB 2.7 没有官方 {@code @ServiceConnection} 工厂，显式注入 v2.0-beta 双 namespace
     * （新 {@code monitor.tsdb.influxdb.*} + 遗留 {@code spring.influx.*}）保证 InfluxDbProvider /
     * deprecated path 都拿到容器地址。
     * <p>
     * 凭证必须与 {@link #INFLUX} 的 {@code withUsername} / {@code withPassword} 完全一致；
     * 不一致会让 v1 兼容鉴权失败，触发 PR2 hotfix 修复的 500 回归。
     */
    @DynamicPropertySource
    static void influxProps(DynamicPropertyRegistry registry) {
        registry.add("monitor.tsdb.influxdb.url", INFLUX::getUrl);
        registry.add("monitor.tsdb.influxdb.user", () -> "monitor");
        registry.add("monitor.tsdb.influxdb.password", () -> "monitor-test-password");
        registry.add("monitor.tsdb.influxdb.bucket", () -> "monitor");
        registry.add("monitor.tsdb.influxdb.organization", () -> "monitor");
        registry.add("spring.influx.url", INFLUX::getUrl);
        registry.add("spring.influx.user", () -> "monitor");
        registry.add("spring.influx.password", () -> "monitor-test-password");
        registry.add("spring.influx.bucket", () -> "monitor");
        registry.add("spring.influx.organization", () -> "monitor");
    }
}
