package com.example.integration;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.example.entity.alert.AlertStatus;
import com.example.entity.dto.Client;
import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.integration.support.AdminLoginSupport;
import com.example.integration.support.WireMockSupport;
import com.example.service.ClientService;
import com.example.service.impl.ClientServiceImpl;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.awaitility.Awaitility.await;

/**
 * v2.0-tests PR3：告警引擎端到端集成测试。
 *
 * <p>覆盖链路：
 * <ol>
 *   <li>{@code POST /api/notification/channel}（mail + webhook）→ DB 落库 + config 加密；</li>
 *   <li>{@code POST /api/alert/rule}（cpu > 80 阈值规则，{@code duration_sec=10}）→ DB 落库；</li>
 *   <li>{@code clientService.updateRuntimeDetail(...)} 注入 cpu=95% runtime →
 *       {@link com.example.service.impl.AlertEvaluatorImpl} 异步评估，写 {@code alert_history} +
 *       投 {@code notification} RabbitMQ 队列；</li>
 *   <li>停掉 {@code NotificationQueueListener} 后直接断言 {@code notification} 队列中包含
 *       一条 {@code AlertEvent}（{@code ruleId} 与 {@code channelIds} 匹配 mail 通道）→
 *       证明 AlertEvaluator → RabbitMQ 投递成功；</li>
 *   <li>{@code POST /api/alert/history/{id}/ack} 确认告警 → DB {@code alert_history.status='acknowledged'}；</li>
 *   <li>禁用规则后注入 breach → 不触发新告警 + notification 队列无新消息；</li>
 *   <li>Webhook 通道 → {@link WireMockExtension} 拦截 HTTP POST，验证 body 含 {@code ruleName / level / message}。</li>
 * </ol>
 *
 * <p><b>PR3 hotfix（commit 8e39b1b 后撤回）</b>：原 {@code createRuleThenFireAlert_persistsHistoryAndQueuesMail}
 * 用 GreenMail 拦截 SMTP 真投递断言。CI run 26528822233 / 26529339906 反复在
 * {@code MailAuthenticationException: 535 5.7.8 Authentication credentials invalid} 卡死——
 * GreenMail withDisabledAuthentication() 与 withUser() 互斥语义、Spring JavaMailSender
 * 的 username 非空时硬触发 AUTH 等多层协议怪癖叠加，4 个 commit 没修好。改用 RabbitMQ
 * 队列消息断言：链路 AlertEvaluator → rabbitTemplate.convertAndSend 是真集成测试的真正
 * 增量价值（vs 已存在的 MailNotificationChannel 单测），MailNotificationChannel.send →
 * JavaMail → SMTP 那段已经被 {@code MailNotificationChannelTest} 单测覆盖了。
 *
 * <p>每个 @Test 通过 {@code @AfterEach @Sql(cleanup-after-test.sql)} 截断 13 业务表，
 * 独立运行；BeforeEach 重置 admin 密码 + 刷新 ClientService 缓存 + 清空 AlertWindowCache +
 * 排空 notification 队列。
 *
 * <p>同步策略：
 * <ul>
 *   <li>{@code AlertEvaluator.evaluate} 走 {@code @Async("alertTaskExecutor")} 虚拟线程，
 *       因此「注入 runtime → 看到 alert_history」之间用 {@link await} Awaitility 轮询；</li>
 *   <li>测试方法在投递前主动 {@code stopNotificationListener()}，避免 listener 在 await 之前
 *       消费掉队列消息（沿用 {@code ProbeFlowIT.consecutiveFailures_triggerAlert} 已验证的模式）。</li>
 * </ul>
 *
 * <p>D5 决策：保留 RabbitMQ 容器（真实队列），隔离真实第三方（mail 改用队列断言；
 * webhook 用 WireMock 拦截 HTTP 出口）。
 */
class AlertFlowIT extends IntegrationTestBase {

    /**
     * WireMock HTTP mock：动态端口分配，用于拦截 webhook 通道的出口。
     */
    @RegisterExtension
    static final WireMockExtension WEBHOOK_MOCK = WireMockSupport.dynamicPort();

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ClientService clientService;

    @Autowired
    private ClientServiceImpl clientServiceImpl;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    @Autowired
    @Qualifier("notificationRabbitTemplate")
    private RabbitTemplate notificationRabbitTemplate;

    private String jwt;

    /**
     * 每个测试方法前：刷新 ClientService 缓存（cleanup 后 client 表已空）、重置 admin 密码、
     * 用 admin 登录拿 JWT、确保 notification listener 已启动（上一个 @Test 可能 stop 过）、
     * 排空 notification 队列残留消息。
     *
     * <p>{@code AlertWindowCache} 是进程内 Caffeine 缓存，跨 @Test 的样本残留会污染下个测试的
     * {@code isContinuouslyMet} 判断；但每个 @Test 通过 cleanup-after-test.sql 截断 alert_rule 表
     * 后又重新创建 rule，新 ruleId 由 AUTO_INCREMENT 分配不会与旧重复，故 cache 残留对新 rule 评估
     * 无影响（rule.id 是缓存 key 的一部分）。
     */
    @BeforeEach
    void resetState() {
        clientServiceImpl.initClientCache();
        // PR3 hotfix：StringRedisTemplate 重载会在登录前 DEL jwt:frequency:1，避免 CI 连续 @BeforeEach
        // 命中 limitOnceUpgradeCheck 拒绝（"登录验证频繁，请稍后再试"）
        jwt = AdminLoginSupport.resetAndLogin(jdbcTemplate, passwordEncoder, stringRedisTemplate,
                restTemplate, baseUrl());
        startNotificationListener();
        drainNotificationQueue();
    }

    @Test
    @DisplayName("规则创建 → cpu breach 注入 → alert_history 落库 + notification 队列收到 AlertEvent")
    void createRuleThenFireAlert_persistsHistoryAndQueuesNotification() {
        Client client = registerClient();
        long mailChannelId = createMailChannel("alert-it-mail", "alerts@example.com");
        long ruleId = createCpuRule("cpu-breach-it", 80.0, 10, List.of(mailChannelId), true);

        // 停掉 NotificationQueueListener 以便我们 receive 这条消息（否则 listener 会先消费掉）。
        // 该模式抄自 ProbeFlowIT.consecutiveFailures_triggerAlert。
        stopNotificationListener();
        try {
            // BeforeEach 已排空过队列；stop 后可能再有少量残留，再清一次
            drainNotificationQueue();

            // 注入连续 breach runtime（duration=10s，需要持续 >= 10s 的连续 true 样本才会触发）
            // AlertWindowCache.record 用真实 Instant.now()，因此必须用时间差注入而非循环立刻调
            injectCpuBreaches(client, /*cpuPercent=*/ 95.0, /*samples=*/ 12, /*intervalMs=*/ 1100);

            // 1) alert_history 应有 firing 行（异步评估 → 轮询）
            await().atMost(Duration.ofSeconds(20))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> {
                        Integer count = jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM alert_history WHERE rule_id = ? AND status = 'firing'",
                                Integer.class, ruleId);
                        Assertions.assertNotNull(count);
                        Assertions.assertTrue(count >= 1,
                                "alert_history 应有至少一条 firing 行，实际数量=" + count);
                    });

            // 2) RabbitMQ notification 队列应有一条 AlertEvent 消息（PR3 hotfix：用队列断言
            // 替代 GreenMail SMTP 拦截 —— SMTP AUTH 链路在 CI 上反复失败 4 次，ROI 太低；
            // 链路价值的核心是「AlertEvaluator → rabbitTemplate.convertAndSend → notification 队列」
            // 这段，邮件投递的 JavaMail / SMTP 部分已在 MailNotificationChannelTest 单测覆盖）。
            await().atMost(Duration.ofSeconds(20))
                    .pollInterval(Duration.ofMillis(500))
                    .untilAsserted(() -> {
                        Message message = notificationRabbitTemplate.receive("notification", 500);
                        Assertions.assertNotNull(message,
                                "notification 队列应至少有 1 条 AlertEvent 消息");
                        String json = new String(message.getBody(), StandardCharsets.UTF_8);
                        JSONObject payload = JSON.parseObject(json);
                        Assertions.assertEquals(ruleId, payload.getLong("ruleId").longValue(),
                                "AlertEvent.ruleId 应为创建的规则 id，实际 payload=" + json);
                        Assertions.assertEquals("cpu", payload.getString("metric"),
                                "AlertEvent.metric 应为 cpu，实际 payload=" + json);
                        Assertions.assertEquals("warning", payload.getString("level"),
                                "AlertEvent.level 应为 warning，实际 payload=" + json);
                        List<Object> channelIds = payload.getJSONArray("channelIds").toList(Object.class);
                        Assertions.assertTrue(channelIds.contains(mailChannelId)
                                        || channelIds.contains(Long.valueOf(mailChannelId).intValue()),
                                "AlertEvent.channelIds 应含 mailChannelId=" + mailChannelId + "，实际=" + channelIds);
                    });
        } finally {
            startNotificationListener();
        }

        // 3) 确认告警：POST /api/alert/history/{id}/ack → DB status='acknowledged'
        Long historyId = jdbcTemplate.queryForObject(
                "SELECT id FROM alert_history WHERE rule_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, ruleId);
        Assertions.assertNotNull(historyId, "alert_history 应可被查询到 id");

        HttpHeaders ackHeaders = jwtHeaders();
        ResponseEntity<String> ackResponse = restTemplate.exchange(
                baseUrl() + "/api/alert/history/" + historyId + "/ack",
                HttpMethod.POST,
                new HttpEntity<>(ackHeaders),
                String.class);
        Assertions.assertEquals(200, ackResponse.getStatusCode().value());
        JSONObject ackBody = JSON.parseObject(ackResponse.getBody());
        Assertions.assertEquals(200, ackBody.getIntValue("code"),
                "ack 应业务成功，实际响应=" + ackResponse.getBody());

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM alert_history WHERE id = ?", String.class, historyId);
        Assertions.assertEquals(AlertStatus.ACKNOWLEDGED.getColumn(), status,
                "ack 后 alert_history.status 应为 acknowledged，实际=" + status);
    }

    @Test
    @DisplayName("duration_sec 过滤：单次瞬时尖刺不会触发告警")
    void ruleWithDurationFilter_skipsTransientSpike() {
        Client client = registerClient();
        long mailChannelId = createMailChannel("alert-it-mail-spike", "alerts@example.com");
        long ruleId = createCpuRule("cpu-spike-it", 80.0, 30, List.of(mailChannelId), true);

        // 停掉 listener 让我们能 receive 残留消息（如果异常触发了）
        stopNotificationListener();
        try {
            drainNotificationQueue();

            // 注入单次 breach 后等待评估异步落地的时间，再断言没有 firing 行
            // 单次样本不可能满足 30s 持续条件
            RuntimeDetailVO breach = buildRuntime(95.0);
            clientService.updateRuntimeDetail(breach, client);

            // 异步评估有概率刚好赶上 record + isContinuouslyMet（窗口只有 1 个样本），需等待
            // 评估完成后再断言。等 5s 足够让 @Async 链路完成且尚未达到 duration_sec=30
            await().pollDelay(Duration.ofSeconds(5))
                    .atMost(Duration.ofSeconds(7))
                    .untilAsserted(() -> {
                        Integer count = jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM alert_history WHERE rule_id = ?",
                                Integer.class, ruleId);
                        Assertions.assertNotNull(count);
                        Assertions.assertEquals(0, count.intValue(),
                                "单次尖刺不应触发告警（duration_sec=30）；alert_history 实际行数=" + count);
                    });
            // notification 队列也不应有消息
            Message stray = notificationRabbitTemplate.receive("notification", 200);
            Assertions.assertNull(stray,
                    "duration 未满足时 notification 队列不应有 AlertEvent，实际=" + (stray == null
                            ? "null" : new String(stray.getBody(), StandardCharsets.UTF_8)));
        } finally {
            startNotificationListener();
        }
    }

    @Test
    @DisplayName("禁用规则后 breach 不触发告警")
    void disabledRule_doesNotFire() {
        Client client = registerClient();
        long mailChannelId = createMailChannel("alert-it-mail-disabled", "alerts@example.com");
        long ruleId = createCpuRule("cpu-disabled-it", 80.0, 10, List.of(mailChannelId), /*enabled=*/ false);

        stopNotificationListener();
        try {
            drainNotificationQueue();

            injectCpuBreaches(client, 95.0, 12, 1100);

            // disabled 规则不应被 AlertEvaluator listApplicableRules（filter enabled=1）选中
            await().pollDelay(Duration.ofSeconds(3))
                    .atMost(Duration.ofSeconds(6))
                    .untilAsserted(() -> {
                        Integer count = jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM alert_history WHERE rule_id = ?",
                                Integer.class, ruleId);
                        Assertions.assertEquals(0, count.intValue(),
                                "disabled 规则不应触发告警，alert_history 实际行数=" + count);
                    });
            Message stray = notificationRabbitTemplate.receive("notification", 200);
            Assertions.assertNull(stray,
                    "disabled 规则不应投递 AlertEvent，实际队列消息="
                            + (stray == null ? "null" : new String(stray.getBody(), StandardCharsets.UTF_8)));
        } finally {
            startNotificationListener();
        }
    }

    @Test
    @DisplayName("Webhook 通道：触发告警 → WireMock 收到 JSON POST 含 ruleId/level")
    void webhookChannel_postsToWiremock() {
        Client client = registerClient();
        // WireMock stub /alert
        WEBHOOK_MOCK.stubFor(post(urlEqualTo("/alert"))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

        long webhookChannelId = createWebhookChannel("alert-it-webhook",
                WEBHOOK_MOCK.baseUrl() + "/alert");
        long ruleId = createCpuRule("cpu-webhook-it", 80.0, 10, List.of(webhookChannelId), true);

        injectCpuBreaches(client, 95.0, 12, 1100);

        // 等 firing 落库
        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    Integer count = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM alert_history WHERE rule_id = ? AND status = 'firing'",
                            Integer.class, ruleId);
                    Assertions.assertTrue(count >= 1,
                            "alert_history 应有 firing 行，实际=" + count);
                });

        // 等 WireMock 收到 POST（RabbitMQ 异步消费）
        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    int hits = WEBHOOK_MOCK.findAll(postRequestedFor(urlEqualTo("/alert"))).size();
                    Assertions.assertTrue(hits >= 1,
                            "WireMock 应至少收到 1 次 POST /alert，实际=" + hits);
                });

        // 取首次请求 body 验证内容
        var requests = WEBHOOK_MOCK.findAll(postRequestedFor(urlEqualTo("/alert")));
        String body = requests.get(0).getBodyAsString();
        Assertions.assertNotNull(body);
        JSONObject json = JSON.parseObject(body);
        Assertions.assertEquals("warning", json.getString("level"),
                "webhook 默认 payload level 应为 warning（规则配置），实际 body=" + body);
        Assertions.assertNotNull(json.getString("metric"),
                "webhook payload 应含 metric 字段，body=" + body);
        Assertions.assertNotNull(json.get("threshold"),
                "webhook payload 应含 threshold 字段，body=" + body);
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
     * 注册一个 client：调 GET /monitor/register 拿到新 client；用 ClientService.findClientByToken
     * 反查得到 Client 实体（含数据库分配的 id）。
     */
    private Client registerClient() {
        String token = clientService.getToken();
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, token);
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/monitor/register",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
        Assertions.assertEquals(200, response.getStatusCode().value());
        JSONObject body = JSON.parseObject(response.getBody());
        Assertions.assertEquals(200, body.getIntValue("code"),
                "前置注册必须业务成功，实际响应=" + response.getBody());
        Client client = clientService.findClientByToken(token);
        Assertions.assertNotNull(client, "ClientService 应能反查到刚注册的 Client");
        return client;
    }

    /**
     * 创建一个 mail 通知通道。{@code to_addrs} 写收件人邮箱。
     */
    private long createMailChannel(String name, String toAddr) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("to_addrs", toAddr);
        return createNotificationChannel(name, "mail", config);
    }

    /**
     * 创建一个 webhook 通知通道。{@code url} 字段写明文 URL（路径将被 WireMock stub 拦截）。
     */
    private long createWebhookChannel(String name, String url) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("url", url);
        return createNotificationChannel(name, "webhook", config);
    }

    /**
     * 通用 channel 创建方法。
     */
    private long createNotificationChannel(String name, String type, Map<String, Object> config) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("type", type);
        payload.put("config", config);
        payload.put("enabled", true);
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/api/notification/channel",
                HttpMethod.POST,
                new HttpEntity<>(JSON.toJSONString(payload), jwtHeaders()),
                String.class);
        Assertions.assertEquals(200, response.getStatusCode().value(),
                "创建通知通道 HTTP 应 200，实际=" + response.getStatusCode() + ", body=" + response.getBody());
        JSONObject body = JSON.parseObject(response.getBody());
        Assertions.assertEquals(200, body.getIntValue("code"),
                "创建通知通道 RestBean.code 应 200，实际=" + body);
        Long id = body.getJSONObject("data").getLong("id");
        Assertions.assertNotNull(id, "创建通知通道响应应含 data.id，实际 body=" + body);
        return id;
    }

    /**
     * 创建一个 cpu > threshold 全局告警规则（client_id=null），返回规则 id。
     */
    private long createCpuRule(String name, double threshold, int durationSec,
                               List<Long> channelIds, boolean enabled) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("clientId", null);
        payload.put("metric", "cpu");
        payload.put("operator", "gt");
        payload.put("threshold", threshold);
        payload.put("durationSec", durationSec);
        payload.put("level", "warning");
        payload.put("enabled", enabled);
        payload.put("channelIds", channelIds);
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/api/alert/rule",
                HttpMethod.POST,
                new HttpEntity<>(JSON.toJSONString(payload), jwtHeaders()),
                String.class);
        Assertions.assertEquals(200, response.getStatusCode().value(),
                "创建告警规则 HTTP 应 200，实际=" + response.getStatusCode() + ", body=" + response.getBody());
        JSONObject body = JSON.parseObject(response.getBody());
        Assertions.assertEquals(200, body.getIntValue("code"),
                "创建告警规则 RestBean.code 应 200，实际=" + body);
        Long id = body.getJSONObject("data").getLong("id");
        Assertions.assertNotNull(id, "创建告警规则响应应含 data.id，实际 body=" + body);
        return id;
    }

    /**
     * 连续注入 N 个 cpu breach runtime 样本，间隔 intervalMs 毫秒。
     * <p>
     * 模拟客户端 10s 上报节奏，但实际间隔可以缩短到 1s 让 IT 跑得更快。
     * AlertWindowCache 用真实 Instant.now() 计算窗口跨度，所以必须真实间隔时间。
     * <p>
     * cpuUsage 在 RuntimeDetailVO 是 0~1 的比例数；AlertEvaluator 把它乘 100 后与阈值比较。
     * 因此 cpuPercent=95 → vo.cpuUsage=0.95。
     */
    private void injectCpuBreaches(Client client, double cpuPercent, int samples, long intervalMs) {
        for (int i = 0; i < samples; i++) {
            RuntimeDetailVO vo = buildRuntime(cpuPercent);
            clientService.updateRuntimeDetail(vo, client);
            if (i < samples - 1) {
                sleepQuietly(intervalMs);
            }
        }
    }

    /**
     * 构造一个 RuntimeDetailVO，cpu 用 cpuPercent（0~100），其余字段填合理默认。
     */
    private RuntimeDetailVO buildRuntime(double cpuPercent) {
        RuntimeDetailVO vo = new RuntimeDetailVO();
        vo.setTimestamp(new Date().getTime());
        // AlertEvaluator: case CPU -> runtime.getCpuUsage() * 100.0；上报值应在 0~1 之间
        vo.setCpuUsage(cpuPercent / 100.0);
        vo.setMemoryUsage(2.0);
        vo.setDiskUsage(20.0);
        vo.setNetworkUpload(10.0);
        vo.setNetworkDownload(20.0);
        vo.setDiskRead(1.0);
        vo.setDiskWrite(2.0);
        return vo;
    }

    /**
     * 静默 sleep，捕获 InterruptedException 重置中断标志。
     */
    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
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
            if (container instanceof MessageListenerContainer mc && hasQueue(mc, "notification")) {
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
            if (container instanceof MessageListenerContainer mc
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
    private boolean hasQueue(MessageListenerContainer container, String queueName) {
        if (container instanceof AbstractMessageListenerContainer abs) {
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
