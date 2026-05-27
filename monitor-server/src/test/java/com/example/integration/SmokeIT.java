package com.example.integration;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * v2.0-tests PR1 验收点：验证集成测试基础设施全链路装配成功。
 *
 * <p>检查项：
 * <ol>
 *   <li>Spring Context 启动成功（隐式：能注入 JdbcTemplate / TestRestTemplate 即 PASS）</li>
 *   <li>4 个 Testcontainers 容器 isRunning：MySQL / Redis / RabbitMQ / InfluxDB</li>
 *   <li>Flyway 已跑（{@code account} 表存在 + Flyway V1 预置 admin 行存在）</li>
 *   <li>{@code /actuator/health} 端点 200 + body 含 {@code "status":"UP"}</li>
 * </ol>
 *
 * <p>所有 PR2 / PR3 的实际业务 IT 在这条 smoke 通过后才可以放心写。
 *
 * <p><b>PR2 hotfix</b>：加 {@link DirtiesContext}({@code BEFORE_CLASS}) 让 SmokeIT 拿到独立的
 * Spring TestContext + 全新的 HikariCP 池。之前 ClientRuntimeIT 跑完后，共享的 Spring Context
 * 复用同一个 HikariCP，CI 上偶发 {@code Connection refused}（MySQL 进程被 docker daemon 在高负载
 * 期间 OOM-killed，但容器 shell 仍然存活，{@link MYSQL#isRunning()} 仍返回 true）。强制刷新 Context
 * 让 Spring 重建 DataSource → Hikari 重新拿到当前可用的 MySQL 端口。
 *
 * <p>同时加 {@link #verifyContainerConnectivity()} 在 Spring TestContext 启动前直接通过
 * {@code DriverManager.getConnection} ping MySQL 容器；若 MySQL 真死了，断言会立刻失败而不是
 * 等 30s HikariCP 超时，便于在 CI 日志中区分"容器死"与"Hikari 状态死"。
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class SmokeIT extends IntegrationTestBase {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * 直接通过 JDBC DriverManager ping MySQL，绕过 Spring 的 HikariCP，
     * 把 SmokeIT 的真实失败定位为"MySQL 容器死亡" vs "Spring 池状态死"。
     */
    private void verifyContainerConnectivity() {
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

    @Test
    @DisplayName("Spring Context 启动 + 4 容器 running + Flyway admin 行存在 + /actuator/health UP")
    void smokeAll() {
        // 0) JDBC 直连诊断：把容器死亡问题与 Spring/Hikari 池状态问题分离
        verifyContainerConnectivity();

        // 1) 4 容器 isRunning
        Assertions.assertTrue(MYSQL.isRunning(), "MySQL container should be running");
        Assertions.assertTrue(REDIS.isRunning(), "Redis container should be running");
        Assertions.assertTrue(RABBIT.isRunning(), "RabbitMQ container should be running");
        Assertions.assertTrue(INFLUX.isRunning(), "InfluxDB container should be running");

        // 2) Flyway 跑过：admin 行存在
        Integer adminCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM account WHERE username = 'admin'", Integer.class);
        Assertions.assertNotNull(adminCount);
        Assertions.assertTrue(adminCount >= 1, "Flyway V1 admin 行应被预置，实际数量=" + adminCount);

        // 3) /actuator/health 端点 200 + UP
        ResponseEntity<String> health = restTemplate.getForEntity(
                "http://localhost:" + port + "/actuator/health", String.class);
        Assertions.assertEquals(200, health.getStatusCode().value(),
                "/actuator/health 应返回 200，实际=" + health.getStatusCode());
        Assertions.assertNotNull(health.getBody());
        Assertions.assertTrue(health.getBody().contains("\"status\":\"UP\""),
                "/actuator/health body 应含 status:UP，实际=" + health.getBody());
    }
}
