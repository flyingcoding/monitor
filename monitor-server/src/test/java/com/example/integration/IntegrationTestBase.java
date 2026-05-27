package com.example.integration;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

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
 *
 * <h3>PR3 hotfix：MySQL OOM 防御</h3>
 * <p>CI runner（GitHub Actions ubuntu-latest，7GB RAM）跑 4 个 Testcontainers + Spring Boot
 * + Maven 时容易把 MySQL 容器 OOM killed。docker 进程 kill 了 mysqld 后 Testcontainers 的
 * {@link MySQLContainer#isRunning()} 返回缓存的 "started" 状态（不去 ping Docker），所以静态字段
 * 还 "alive"，但实际 mysqld 已死 → Hikari pool 在 @BeforeEach 拿连接 60s 后 timeout。
 * 全套 ProbeFlowIT 5 @Test × 60s = 5min 卡死的元凶。
 *
 * <p>两层防御：
 * <ol>
 *   <li><b>压低 MySQL 内存占用</b>：{@code .withCommand(--innodb-buffer-pool-size=64M, --max-connections=20,
 *       --innodb-flush-method=O_DIRECT_NO_FSYNC)} 让 MySQL 默认 ~128MB buffer pool 砍半 + max-connections
 *       从 151 降到 20（每个连接 thread 内存巨大），并放宽 fsync 压力。从源头降低 OOM 概率。</li>
 *   <li><b>{@link DirtiesContext}({@code BEFORE_CLASS})</b>：所有 IT 共享 fail-fast 隔离——每个 IT 类
 *       拿独立 Spring TestContext + 全新 HikariCP 池。容器实例不变（{@code static final} reuse），但
 *       Spring 重建 DataSource 避免上一个 IT 留下的死连接污染下一个 IT。</li>
 *   <li><b>{@link #verifyContainerConnectivity()} @BeforeAll</b>：每个 IT 类启动时主动 JDBC 直连 ping
 *       MySQL，绕开 Spring 的 HikariCP 池。若 MySQL 真死了，断言立刻失败（< 5s）而不是等 Hikari 60s 超时；
 *       便于在 CI 日志中区分"容器死"与"Hikari 状态死"。</li>
 * </ol>
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@Sql(scripts = "/cleanup-after-test.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
public abstract class IntegrationTestBase {

    /**
     * MySQL 容器：JUnit5 {@code @Testcontainers} 在 JVM 内通过 {@code static final} 字段把容器
     * 跨测试类共享，但 Spring TestContext 即使被复用，HikariCP 连接池仍可能在 IT 之间出现
     * 抖动（GitHub Actions docker daemon 高负载时 MySQL 进程偶有 OOM）。
     * <p>
     * 调试 tmpfs 挂载方案后已验证它会 <b>增加</b> 单进程内存压力（数据全驻留内存）→ 反加重 OOM，
     * 故不引入 {@code withTmpFs}。
     * <p>
     * <b>PR3 hotfix</b>：{@code withCommand} 限制 mysqld 内存：
     * <ul>
     *   <li>{@code --innodb-buffer-pool-size=64M}：默认 128M，砍半省一半驻留内存。</li>
     *   <li>{@code --max-connections=20}：默认 151，每个 connection thread 占巨量内存；20 已足够 IT
     *       使用（Hikari maximum-pool-size=20 也对齐）。</li>
     *   <li>{@code --innodb-flush-method=O_DIRECT_NO_FSYNC}：放宽 fsync，减少 IO wait；CI 数据不需持久化。</li>
     *   <li>{@code --performance-schema=OFF}：performance_schema 默认占用 ~200MB；测试无需性能监控，关掉。</li>
     * </ul>
     */
    @Container
    @ServiceConnection
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("monitor")
            .withUsername("test")
            .withPassword("test")
            .withCommand(
                    "--innodb-buffer-pool-size=64M",
                    "--max-connections=20",
                    "--innodb-flush-method=O_DIRECT_NO_FSYNC",
                    "--performance-schema=OFF"
            )
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

    /**
     * 每个 IT 类启动前直接通过 JDBC {@link DriverManager} ping MySQL 容器，绕过 Spring HikariCP 池，
     * 把"MySQL 容器死亡"和"Spring 池状态死"两类失败明确区分。
     *
     * <p><b>背景</b>：CI runner 7GB RAM 跑 4 容器 + Spring Boot + Maven 触发 MySQL OOM 时，docker
     * 进程 kill 了 mysqld 但 Testcontainers 的 {@link MySQLContainer#isRunning()} 返回缓存的
     * "started" 状态（不去 ping Docker），所以静态字段还 "alive"，但实际 mysqld 已死 → Hikari pool
     * 在 @BeforeEach 拿连接 60s 后 timeout，每个 @Test 5 分钟卡死。
     *
     * <p>本方法在 Spring TestContext 初始化前用 {@link DriverManager} 直连，若 MySQL 真死了，
     * 断言立刻失败给出清晰的 {@code MySQL container is running but JDBC connect failed} 错误，
     * 而不是漫长的 60s timeout（× N 个 @Test）。
     *
     * <p><b>为什么 static @BeforeAll 而不是 @BeforeEach</b>：容器是单例 {@code static final}，
     * 死/活状态在测试方法之间不会变化（除非 OOM）。在类启动前 ping 一次就够了；放到 @BeforeEach
     * 会增加每个 @Test ~10ms 不必要开销。
     */
    @BeforeAll
    static void verifyContainerConnectivity() {
        // 容器层：Testcontainers 看容器是否运行
        Assertions.assertTrue(MYSQL.isRunning(),
                "MySQL container should be running (Testcontainers level)");

        // 进程层：JDBC 直连，绕过 Spring，判断 mysqld 进程是否真活着
        String url = MYSQL.getJdbcUrl();
        try (Connection conn = DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT 1");
        } catch (SQLException e) {
            throw new AssertionError(
                    "MySQL container is running but JDBC connect failed: url=" + url
                            + ", error=" + e.getMessage(), e);
        }
    }
}
