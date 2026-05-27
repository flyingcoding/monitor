package com.example.integration;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.example.entity.dto.ProbeTask;
import com.example.integration.support.AdminLoginSupport;
import com.example.integration.support.WireMockSupport;
import com.example.service.ProbeService;
import com.example.service.impl.ProbeScheduler;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.awaitility.Awaitility.await;

/**
 * v2.0-tests PR3：服务探测引擎端到端集成测试。
 *
 * <p>覆盖链路：
 * <ol>
 *   <li>{@code POST /api/probes} 创建 HTTP / TCP probe → DB 落库（headers / basic auth 加密入库）；</li>
 *   <li>直接调用 {@link ProbeScheduler#runSingle(ProbeTask)} 触发一次执行（绕过 5s tick）→
 *       {@link com.example.service.impl.probe.HttpProbeExecutor} / {@code TcpProbeExecutor} 真实发起
 *       HTTP/Socket 连接，被 {@link WireMockExtension} / 进程内 ServerSocket 接收；</li>
 *   <li>{@code probe_history} 表落入一条记录，{@code success / latency_ms / status_code} 字段正确；</li>
 *   <li>HTTP probe 带 Basic Auth 时 → WireMock 收到带 {@code Authorization: Basic ...} 头的请求；</li>
 *   <li>连续失败 N 次（{@code consecutiveFailuresThreshold}）→
 *       {@link ProbeScheduler#evaluateAndAlert(ProbeTask, com.example.service.impl.probe.ProbeResult)}
 *       投递 AlertEvent 到 RabbitMQ {@code notification} 队列（rabbitTemplate.receive 验证）。</li>
 * </ol>
 *
 * <p>同步策略：
 * <ul>
 *   <li>{@link ProbeScheduler#runSingle(ProbeTask)} 是同步执行；不需要等 5s tick，避免引入
 *       不必要的延迟。{@code @Scheduled} 注解的 tick 方法仍会自动触发，但本 IT 不依赖它。</li>
 *   <li>RabbitMQ 消息消费侧不需要等：直接用 {@link RabbitTemplate#receive(String, long)} 从
 *       {@code notification} 队列读消息（{@code NotificationQueueListener} 会与 receive 竞争
 *       同一条消息，因此本 IT 用 {@code RabbitListenerEndpointRegistry.stop()} 关闭 listener
 *       后再 receive）。</li>
 * </ul>
 *
 * <p>D5 决策：保留 RabbitMQ 容器（真实队列），WireMock 拦截 HTTP 出口避免外网依赖。
 */
class ProbeFlowIT extends IntegrationTestBase {

    /**
     * WireMock HTTP mock：动态端口分配，用作 HTTP probe 目标。
     */
    @RegisterExtension
    static final WireMockExtension HTTP_MOCK = WireMockSupport.dynamicPort();

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ProbeScheduler probeScheduler;

    @Autowired
    private ProbeService probeService;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @Autowired
    private org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry listenerRegistry;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("notificationRabbitTemplate")
    private RabbitTemplate notificationRabbitTemplate;

    private String jwt;

    /**
     * 每个测试方法前：清空 ProbeScheduler 进程内状态（failureCounters / lastRunAt）、
     * 重置 admin 密码、登录拿 JWT。
     *
     * <p>{@link ProbeScheduler#clearStateForTest()} 必须主动调用：cleanup-after-test.sql
     * 只清 DB 不清进程内 ConcurrentHashMap，跨 @Test 的失败计数会累计触发意外告警。
     */
    @BeforeEach
    void resetState() {
        probeScheduler.clearStateForTest();
        // PR3 hotfix：StringRedisTemplate 重载会在登录前 DEL jwt:frequency:1，避免 CI 连续 @BeforeEach
        // 命中 limitOnceUpgradeCheck 拒绝（"登录验证频繁，请稍后再试"）
        jwt = AdminLoginSupport.resetAndLogin(jdbcTemplate, passwordEncoder, stringRedisTemplate,
                restTemplate, baseUrl());
        // 排空 notification 队列残留（上一个 @Test 已 stop listener 后留下的、或者其他 IT 顺带产生的消息）
        drainNotificationQueue();
    }

    @Test
    @DisplayName("HTTP probe 200 → probe_history 写入 success=true + status_code=200")
    void httpProbeWith200_recordsSuccessHistory() {
        HTTP_MOCK.stubFor(get(urlEqualTo("/healthz"))
                .willReturn(aResponse().withStatus(200).withBody("OK")));

        long taskId = createHttpProbe("probe-it-200", HTTP_MOCK.baseUrl() + "/healthz", null, null, 2);
        ProbeTask task = probeService.getById(taskId);
        Assertions.assertNotNull(task, "ProbeTask 应能从 DB 反查");

        // 直接同步执行一次：绕过 5s @Scheduled tick
        probeScheduler.runSingle(task);

        // 断言 probe_history 一行 success=true
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    Map<String, Object> row = queryLatestHistory(taskId);
                    Assertions.assertNotNull(row, "probe_history 应有至少 1 行，实际查不到");
                    Assertions.assertEquals(Boolean.TRUE, row.get("success"),
                            "probe_history.success 应为 true，实际=" + row.get("success"));
                    Assertions.assertEquals(200, ((Number) row.get("status_code")).intValue(),
                            "probe_history.status_code 应为 200，实际=" + row.get("status_code"));
                    Integer latency = (Integer) row.get("latency_ms");
                    Assertions.assertNotNull(latency, "latency_ms 应非空");
                    Assertions.assertTrue(latency >= 0,
                            "latency_ms 应 >= 0，实际=" + latency);
                });

        // WireMock 应至少记录到一次 GET（@Scheduled tick 每 5s 触发一次，可能并发触发额外探测）
        Assertions.assertTrue(
                HTTP_MOCK.findAll(getRequestedFor(urlEqualTo("/healthz"))).size() >= 1,
                "WireMock 应至少收到 1 次 GET /healthz");
    }

    @Test
    @DisplayName("HTTP probe 500 → probe_history 写入 success=false + status_code=500")
    void httpProbeWith500_recordsFailureHistory() {
        HTTP_MOCK.stubFor(get(urlEqualTo("/fail"))
                .willReturn(aResponse().withStatus(500).withBody("internal error")));

        long taskId = createHttpProbe("probe-it-500", HTTP_MOCK.baseUrl() + "/fail", null, null, 5);
        ProbeTask task = probeService.getById(taskId);
        probeScheduler.runSingle(task);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    Map<String, Object> row = queryLatestHistory(taskId);
                    Assertions.assertNotNull(row, "probe_history 应有 1 行");
                    Assertions.assertEquals(Boolean.FALSE, row.get("success"),
                            "probe_history.success 应为 false（HTTP 500 非 2xx）");
                    Assertions.assertEquals(500, ((Number) row.get("status_code")).intValue(),
                            "probe_history.status_code 应为 500，实际=" + row.get("status_code"));
                });
    }

    @Test
    @DisplayName("HTTP probe with Basic Auth → WireMock 收到 Authorization: Basic 头")
    void httpProbeWithBasicAuth_passesEncryptedCredentials() {
        HTTP_MOCK.stubFor(get(urlEqualTo("/secured"))
                .willReturn(aResponse().withStatus(200)));

        long taskId = createHttpProbe("probe-it-basic", HTTP_MOCK.baseUrl() + "/secured",
                "alice", "s3cret-pwd", 2);
        ProbeTask task = probeService.getById(taskId);
        // 验证密文确实落库（不是明文）
        Assertions.assertNotNull(task.getBasicAuthPasswordEnc(),
                "basic_auth_password_enc 应已加密落库");
        Assertions.assertNotEquals("s3cret-pwd", task.getBasicAuthPasswordEnc(),
                "DB 中的密码字段必须是密文，不能为明文");

        probeScheduler.runSingle(task);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    Map<String, Object> row = queryLatestHistory(taskId);
                    Assertions.assertNotNull(row);
                    Assertions.assertEquals(Boolean.TRUE, row.get("success"));
                });

        // 验证 WireMock 实际收到的请求带 Authorization 头
        // alice:s3cret-pwd → Base64("alice:s3cret-pwd") = "YWxpY2U6czNjcmV0LXB3ZA=="
        String expectedAuth = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("alice:s3cret-pwd".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Assertions.assertTrue(
                HTTP_MOCK.findAll(getRequestedFor(urlEqualTo("/secured"))
                        .withHeader("Authorization", equalTo(expectedAuth))).size() >= 1,
                "WireMock 应至少收到 1 次带 Basic Auth 头的 GET /secured");
    }

    @Test
    @DisplayName("TCP probe 探测进程内开的 ServerSocket → success=true")
    void tcpProbeWithReachableHost_succeeds() throws IOException {
        // 在本机随机端口开 ServerSocket，作为 TCP probe 目标
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int targetPort = serverSocket.getLocalPort();
            long taskId = createTcpProbe("probe-it-tcp", "127.0.0.1:" + targetPort, 2);
            ProbeTask task = probeService.getById(taskId);

            probeScheduler.runSingle(task);

            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> {
                        Map<String, Object> row = queryLatestHistory(taskId);
                        Assertions.assertNotNull(row);
                        Assertions.assertEquals(Boolean.TRUE, row.get("success"),
                                "TCP probe 连到开放端口应成功，实际=" + row.get("success"));
                        Integer latency = (Integer) row.get("latency_ms");
                        Assertions.assertNotNull(latency);
                        Assertions.assertTrue(latency >= 0);
                    });
        }
    }

    @Test
    @DisplayName("连续失败到达阈值 → notification 队列收到 AlertEvent")
    void consecutiveFailures_triggerAlert() {
        HTTP_MOCK.stubFor(get(urlEqualTo("/dead"))
                .willReturn(aResponse().withStatus(503)));

        // consecutiveFailuresThreshold=2 → 第 2 次失败时投递 AlertEvent
        long taskId = createHttpProbe("probe-it-failover", HTTP_MOCK.baseUrl() + "/dead", null, null, 2);
        ProbeTask task = probeService.getById(taskId);

        // 停掉 notification listener，确保我们直接 receive 消息（否则 listener 会消费掉）
        stopNotificationListener();

        try {
            // 排空可能残留的消息（BeforeEach drain 过一次，但 listener stop 后队列可能再入新消息）
            drainNotificationQueue();

            // 触发 2 次失败 → 计数器累积到阈值 → AlertEvent 入队
            probeScheduler.runSingle(task);
            probeScheduler.runSingle(task);

            // 断言 notification 队列里有一条消息
            await().atMost(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> {
                        Message message = notificationRabbitTemplate.receive("notification", 500);
                        Assertions.assertNotNull(message,
                                "notification 队列应至少有 1 条 AlertEvent 消息");
                        String json = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
                        JSONObject payload = JSON.parseObject(json);
                        Assertions.assertEquals("probe", payload.getString("metric"),
                                "AlertEvent.metric 应为 \"probe\"（ProbeScheduler.publishFailureAlert 固定写入）" +
                                        "，实际 payload=" + json);
                        Assertions.assertEquals("probe-it-failover", payload.getString("clientName"),
                                "AlertEvent.clientName 应为 probe 任务名（ProbeScheduler 约定），实际=" + json);
                    });
        } finally {
            startNotificationListener();
        }
    }

    // ===== 辅助方法 =====

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private HttpHeaders jwtHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /**
     * 创建一个 HTTP probe 任务，返回 id。
     *
     * @param name              任务名
     * @param target            探测 URL
     * @param basicAuthUsername Basic Auth 用户名（可空）
     * @param basicAuthPassword Basic Auth 密码明文（可空）
     * @param failThreshold     连续失败阈值
     */
    private long createHttpProbe(String name, String target, String basicAuthUsername,
                                 String basicAuthPassword, int failThreshold) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("type", "http");
        payload.put("target", target);
        payload.put("intervalSec", 60);
        payload.put("timeoutSec", 5);
        payload.put("consecutiveFailuresThreshold", failThreshold);
        payload.put("enabled", true);
        if (basicAuthUsername != null) {
            payload.put("basicAuthUsername", basicAuthUsername);
        }
        if (basicAuthPassword != null) {
            payload.put("basicAuthPassword", basicAuthPassword);
        }
        return createProbe(payload);
    }

    private long createTcpProbe(String name, String target, int failThreshold) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("type", "tcp");
        payload.put("target", target);
        payload.put("intervalSec", 60);
        payload.put("timeoutSec", 5);
        payload.put("consecutiveFailuresThreshold", failThreshold);
        payload.put("enabled", true);
        return createProbe(payload);
    }

    private long createProbe(Map<String, Object> payload) {
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/api/probes",
                HttpMethod.POST,
                new HttpEntity<>(JSON.toJSONString(payload), jwtHeaders()),
                String.class);
        Assertions.assertEquals(200, response.getStatusCode().value(),
                "创建探测任务 HTTP 应 200，实际=" + response.getStatusCode() + ", body=" + response.getBody());
        JSONObject body = JSON.parseObject(response.getBody());
        Assertions.assertEquals(200, body.getIntValue("code"),
                "创建探测任务 RestBean.code 应 200，实际=" + body);
        Long id = body.getJSONObject("data").getLong("id");
        Assertions.assertNotNull(id, "创建探测任务响应应含 data.id，实际 body=" + body);
        return id;
    }

    /**
     * 查询某 task 最新一条 probe_history（按 executed_at 倒序），返回 Map 表示行。
     */
    private Map<String, Object> queryLatestHistory(Long taskId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT success, latency_ms, status_code, ssl_days_remaining, error_message " +
                        "FROM probe_history WHERE task_id = ? ORDER BY id DESC LIMIT 1",
                taskId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 排空 notification 队列：用 RabbitTemplate.receive 在 0.2s 超时下不断弹出消息。
     */
    private void drainNotificationQueue() {
        for (int i = 0; i < 50; i++) {
            Message msg = notificationRabbitTemplate.receive("notification", 200);
            if (msg == null) {
                return;
            }
        }
    }

    /**
     * 停掉 RabbitListener 容器：通过 RabbitListenerEndpointRegistry 遍历所有 endpoint
     * 找到监听 notification 队列的那个，调用 stop()。
     */
    private void stopNotificationListener() {
        listenerRegistry.getListenerContainers().forEach(container -> {
            if (container instanceof org.springframework.amqp.rabbit.listener.MessageListenerContainer mc
                    && hasQueue(mc, "notification")) {
                mc.stop();
            }
        });
    }

    /**
     * 重新启动 notification listener。@AfterEach 不需要显式调用，因为 finally 已经处理；
     * 但被其他测试方法误调时 idempotent 安全。
     */
    private void startNotificationListener() {
        listenerRegistry.getListenerContainers().forEach(container -> {
            if (container instanceof org.springframework.amqp.rabbit.listener.MessageListenerContainer mc
                    && hasQueue(mc, "notification")
                    && !mc.isRunning()) {
                mc.start();
            }
        });
    }

    /**
     * 判断 MessageListenerContainer 是否监听指定 queue。AbstractMessageListenerContainer
     * 上有 {@code getQueueNames()} 但接口未暴露，用反射降级到 toString 中 grep 队列名。
     */
    private boolean hasQueue(org.springframework.amqp.rabbit.listener.MessageListenerContainer container,
                             String queueName) {
        if (container instanceof org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer abs) {
            for (String q : abs.getQueueNames()) {
                if (queueName.equals(q)) {
                    return true;
                }
            }
            return false;
        }
        return container.toString().contains(queueName);
    }
}
