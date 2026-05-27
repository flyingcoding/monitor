package com.example.integration;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

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
 */
class SmokeIT extends IntegrationTestBase {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("Spring Context 启动 + 4 容器 running + Flyway admin 行存在 + /actuator/health UP")
    void smokeAll() {
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
