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

    @Container
    @ServiceConnection
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("monitor")
            .withUsername("test")
            .withPassword("test")
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

    @Container
    protected static final InfluxDBContainer<?> INFLUX = new InfluxDBContainer<>(DockerImageName.parse("influxdb:2.7"))
            .withAdmin("admin")
            .withAdminPassword("admin123456")
            .withOrganization("monitor")
            .withBucket("monitor")
            .withReuse(true);

    /**
     * InfluxDB 2.7 没有官方 {@code @ServiceConnection} 工厂，显式注入 v2.0-beta 双 namespace
     * （新 {@code monitor.tsdb.influxdb.*} + 遗留 {@code spring.influx.*}）保证 InfluxDbProvider /
     * deprecated path 都拿到容器地址。
     */
    @DynamicPropertySource
    static void influxProps(DynamicPropertyRegistry registry) {
        registry.add("monitor.tsdb.influxdb.url", INFLUX::getUrl);
        registry.add("monitor.tsdb.influxdb.user", () -> "admin");
        registry.add("monitor.tsdb.influxdb.password", () -> "admin123456");
        registry.add("monitor.tsdb.influxdb.bucket", () -> "monitor");
        registry.add("monitor.tsdb.influxdb.organization", () -> "monitor");
        registry.add("spring.influx.url", INFLUX::getUrl);
        registry.add("spring.influx.user", () -> "admin");
        registry.add("spring.influx.password", () -> "admin123456");
        registry.add("spring.influx.bucket", () -> "monitor");
        registry.add("spring.influx.organization", () -> "monitor");
    }
}
