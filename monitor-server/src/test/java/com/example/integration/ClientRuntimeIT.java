package com.example.integration;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.example.entity.dto.Client;
import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.entity.vo.response.RuntimeHistoryVO;
import com.example.integration.support.AdminLoginSupport;
import com.example.service.ClientService;
import com.example.service.impl.ClientServiceImpl;
import com.example.tsdb.TimeSeriesAdapter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;

/**
 * v2.0-tests PR2：客户端注册 + 运行时上报端到端集成测试。
 *
 * <p>覆盖链路：
 * <ol>
 *   <li>非法 token 调 {@code GET /monitor/register} → 服务端拒绝；</li>
 *   <li>合法 token（{@code clientService.getToken()} 动态获取的 24 字符随机串）注册成功 →
 *       MySQL {@code client} 表落库；</li>
 *   <li>注册后客户端用持久化 token 调 {@code POST /monitor/runtime/batch} →
 *       {@link TimeSeriesAdapter} 真实写入 InfluxDB（通过读路径反查验证）；</li>
 *   <li>管理员（{@code admin / AdminLoginSupport.KNOWN_ADMIN_PASSWORD}，每个 @Test 在
 *       {@link #resetIntegrationState()} 内通过真实 {@code PasswordEncoder} 重置）登录拿 JWT →
 *       订阅 {@code GET /api/sse/runtime/{clientId}?token=...} →
 *       推送一次 runtime → 真实接收 {@code event: runtime} 帧。</li>
 * </ol>
 *
 * <p>所有断言都穿透 4 个 Testcontainers，不使用 mock。InfluxDB 验证通过 {@link TimeSeriesAdapter}
 * 接口注入避免重写 Flux 查询；SSE 验证用 JDK {@link HttpClient} 流式读取（避开引入 spring-webflux）。
 *
 * <p>测试间隔离：每个 {@code @Test} 通过 {@link IntegrationTestBase} 上挂的
 * {@code @Sql(AFTER_TEST_METHOD)} 清表，并在 {@link #resetIntegrationState()} 主动失效 Caffeine 缓存
 * 防止 {@code ClientServiceImpl#findClientByToken} 命中前一个测试残留；同时重置 admin 密码
 * 让 form 登录可用。
 */
class ClientRuntimeIT extends IntegrationTestBase {

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
    private TimeSeriesAdapter timeSeriesAdapter;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * 每个测试方法前：
     * <ol>
     *   <li>调用 {@link ClientServiceImpl#initClientCache()} 让 Caffeine
     *       {@code clientIdCache / clientTokenCache} 与 cleanup-after-test.sql 截断后的 DB 状态对齐，
     *       避免上一个 @Test 注册的 client 仍留在缓存里被命中；</li>
     *   <li>调用 {@link AdminLoginSupport#resetAdminPassword(JdbcTemplate, PasswordEncoder)}
     *       把 admin 行的 BCrypt 哈希覆盖为 {@link AdminLoginSupport#KNOWN_ADMIN_PASSWORD} 的运行时哈希——
     *       Flyway V1 预置的哈希明文未文档化于代码中，无法在测试里直接 form-login。</li>
     * </ol>
     */
    @BeforeEach
    void resetIntegrationState() {
        clientServiceImpl.initClientCache();
        AdminLoginSupport.resetAdminPassword(jdbcTemplate, passwordEncoder);
    }

    @Test
    @DisplayName("非法 token 调 /monitor/register → 服务端 200 但 RestBean.code=401（业务失败）")
    void registerClient_withInvalidToken_returnsBusinessFailure() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "obviously-not-the-token");
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl() + "/monitor/register",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        Assertions.assertEquals(200, response.getStatusCode().value(),
                "/monitor/register HTTP 状态恒 200，业务错误码走 RestBean.code");
        JSONObject body = JSON.parseObject(response.getBody());
        Assertions.assertNotNull(body, "响应 body 不应为空，实际=" + response.getBody());
        Assertions.assertEquals(401, body.getIntValue("code"),
                "非法 token 注册应在 RestBean.code 返回 401，实际=" + body);

        // DB 不应被污染：client 表应仍为空
        Integer clientCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM client", Integer.class);
        Assertions.assertEquals(0, clientCount,
                "非法 token 注册不应落库，client 表实际行数=" + clientCount);
    }

    @Test
    @DisplayName("合法 token 调 /monitor/register → client 行入库 + Service 缓存命中")
    void registerClient_withValidToken_persistsClientRow() {
        String validToken = clientService.getToken();
        Assertions.assertNotNull(validToken, "ClientService 必须返回当前轮换的注册 token");
        Assertions.assertEquals(24, validToken.length(),
                "ClientServiceImpl#createNewToken 约定 24 字符，实际长度=" + validToken.length());

        ResponseEntity<String> response = registerWithToken(validToken);
        Assertions.assertEquals(200, response.getStatusCode().value());
        JSONObject body = JSON.parseObject(response.getBody());
        Assertions.assertEquals(200, body.getIntValue("code"),
                "合法 token 注册应业务成功，实际响应=" + response.getBody());

        // DB：client 表应有恰好 1 行，token 列匹配
        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, name, token, location, node FROM client");
        Assertions.assertEquals(1, rows.size(),
                "注册成功后 client 表应有 1 行，实际行数=" + rows.size());
        Assertions.assertEquals(validToken, rows.get(0).get("token"),
                "落库的 token 列必须与注册时使用的 token 一致，实际行=" + rows.get(0));

        // Service 层缓存：findClientByToken 应能拿到入库的 Client
        Client cached = clientService.findClientByToken(validToken);
        Assertions.assertNotNull(cached,
                "ClientService 缓存应回填新注册的 client，但 findClientByToken 返回 null");
        Assertions.assertEquals(validToken, cached.getToken(),
                "缓存中的 Client.token 必须与注册 token 一致");
    }

    @Test
    @DisplayName("注册 → POST /monitor/runtime/batch → InfluxDB runtime measurement 真实写入")
    void runtimeBatchAfterRegistration_writesToInfluxDb() {
        Client client = registerAndFetchClient();
        long batchEpochMs = Instant.now().toEpochMilli();

        // 构造 2 条 batch，时间戳差 1s，便于断言历史读出 ≥ 1 个点
        String batchPayload = String.format("""
                [
                  {
                    "timestamp": %d,
                    "cpuUsage": 12.5,
                    "memoryUsage": 34.7,
                    "diskUsage": 56.0,
                    "networkUpload": 100.0,
                    "networkDownload": 200.0,
                    "diskRead": 7.5,
                    "diskWrite": 8.5
                  },
                  {
                    "timestamp": %d,
                    "cpuUsage": 22.5,
                    "memoryUsage": 44.7,
                    "diskUsage": 66.0,
                    "networkUpload": 110.0,
                    "networkDownload": 210.0,
                    "diskRead": 8.5,
                    "diskWrite": 9.5
                  }
                ]
                """, batchEpochMs, batchEpochMs + 1000);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, client.getToken());
        ResponseEntity<String> postRes = restTemplate.exchange(
                baseUrl() + "/monitor/runtime/batch",
                HttpMethod.POST,
                new HttpEntity<>(batchPayload, headers),
                String.class);
        Assertions.assertEquals(200, postRes.getStatusCode().value(),
                "POST /monitor/runtime/batch 应返回 200，实际响应=" + postRes.getBody());
        JSONObject body = JSON.parseObject(postRes.getBody());
        Assertions.assertEquals(200, body.getIntValue("code"),
                "RestBean.code 应为 200，实际=" + postRes.getBody());

        // Caffeine currentRuntime 应被写入（latest 是 batch 第二条）
        RuntimeDetailVO latest = clientService.clientRuntimeDetailsNow(client.getId());
        Assertions.assertNotNull(latest, "ClientService 内存 currentRuntime 应有 latest 记录");
        Assertions.assertEquals(22.5, latest.getCpuUsage(), 0.0001,
                "latest CPU 应等于 batch 末条的 22.5%，实际=" + latest.getCpuUsage());

        // InfluxDB 反查：通过 TimeSeriesAdapter 接口读历史；窗口取 ±1min 容忍写入延迟
        // Awaitility 轮询：InfluxDB 写入虽然走 WriteApiBlocking，但 readBackBytes 短时间内可能未就绪
        Instant from = Instant.ofEpochMilli(batchEpochMs).minus(Duration.ofMinutes(1));
        Instant to = Instant.ofEpochMilli(batchEpochMs).plus(Duration.ofMinutes(1));
        await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    RuntimeHistoryVO history = timeSeriesAdapter.readRuntimeHistory(client.getId(), from, to);
                    Assertions.assertNotNull(history, "TimeSeriesAdapter 读历史不应为 null");
                    Assertions.assertFalse(history.getList().isEmpty(),
                            "InfluxDB runtime measurement 应至少写入 1 个点，实际读出 "
                                    + history.getList().size() + " 个");
                    // 至少一个点的 cpuUsage 字段非空（裸值由 Flux 反查回 Double）
                    boolean hasCpu = history.getList().stream()
                            .anyMatch(row -> row.containsKey("cpuUsage"));
                    Assertions.assertTrue(hasCpu,
                            "至少一个返回点应包含 cpuUsage 字段，实际首条=" + history.getList().get(0));
                });
    }

    @Test
    @DisplayName("管理员登录 → 订阅 /api/sse/runtime/{id} → 推送 runtime → 收到 SSE 事件")
    void sseStreamPushesRuntimeEventAfterBatch() throws Exception {
        Client client = registerAndFetchClient();
        String jwt = loginAsAdmin();
        Assertions.assertNotNull(jwt, "管理员登录应返回非空 JWT");

        // 用 JDK HttpClient 流式订阅 SSE，避免引入 webflux
        // SSE 协议帧示例：
        //   event:runtime\n
        //   data:{"timestamp":...,"cpuUsage":...}\n
        //   \n
        URI sseUri = URI.create(baseUrl() + "/api/sse/runtime/" + client.getId() + "?token=" + jwt);
        HttpClient sseClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest sseRequest = HttpRequest.newBuilder(sseUri)
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        List<String> receivedLines = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> streamError = new AtomicReference<>();

        CompletableFuture<HttpResponse<Void>> streamFuture = sseClient.sendAsync(
                sseRequest,
                HttpResponse.BodyHandlers.fromLineSubscriber(new java.util.concurrent.Flow.Subscriber<>() {
                    @Override
                    public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                        // 请求无限多帧；JDK 实现保证流水线消费，不会内存爆炸
                        subscription.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(String line) {
                        receivedLines.add(line);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        streamError.set(throwable);
                    }

                    @Override
                    public void onComplete() {
                        // SSE 流终结：不主动 cancel，依赖 GC + 后续 await 的 stream done 检测
                    }
                }));

        // 等 HTTP 响应头到达（200 OK + Content-Type: text/event-stream）
        // 头到达后才能开始读 body line；这是判断 "SSE 连接已建立" 的可靠信号
        // 注意：fromLineSubscriber 不会在收到头后立刻 complete future，future 仅在 body 流结束时完成
        // 所以我们用反复推送 + 接收侧轮询的策略避免 race condition
        // 持续推送 runtime 帧直到 SSE 端接到一次（避免 SSE 订阅与第一次 publish 的时序竞态）
        Thread publishLoop = new Thread(() -> {
            for (int i = 0; i < 30; i++) {
                if (Thread.currentThread().isInterrupted()) return;
                try {
                    RuntimeDetailVO push = new RuntimeDetailVO();
                    push.setTimestamp(Instant.now().toEpochMilli());
                    push.setCpuUsage(77.7);
                    push.setMemoryUsage(55.5);
                    push.setDiskUsage(33.3);
                    push.setNetworkUpload(11.1);
                    push.setNetworkDownload(22.2);
                    push.setDiskRead(3.3);
                    push.setDiskWrite(4.4);
                    clientService.updateRuntimeDetail(push, client);
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ignore) {
                    // 重试下一轮
                }
            }
        }, "client-runtime-it-publish-loop");
        publishLoop.setDaemon(true);
        publishLoop.start();

        try {
            // 等待 SSE 端真实收到一次 runtime 事件（event:runtime + data 行）
            await()
                    .atMost(Duration.ofSeconds(20))
                    .pollInterval(Duration.ofMillis(200))
                    .untilAsserted(() -> {
                        Assertions.assertNull(streamError.get(),
                                "SSE 流不应产生错误，实际异常=" + streamError.get());
                        Assertions.assertFalse(streamFuture.isCompletedExceptionally(),
                                "SSE 流不应异常终止");
                        boolean hasRuntimeEvent = receivedLines.stream()
                                .anyMatch(line -> line.startsWith("event:runtime")
                                        || line.contains("\"cpuUsage\":77.7"));
                        Assertions.assertTrue(hasRuntimeEvent,
                                "未收到 runtime SSE 事件，已接收行数=" + receivedLines.size()
                                        + "，最近 5 行=" + receivedLines.subList(
                                        Math.max(0, receivedLines.size() - 5), receivedLines.size()));
                    });
        } finally {
            publishLoop.interrupt();
            streamFuture.cancel(true);
        }
    }

    // ===== 辅助 =====

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    /**
     * 用当前 ClientService 派发的合法 token 调注册端点，断言成功并返回服务端缓存中的 Client。
     */
    private Client registerAndFetchClient() {
        String validToken = clientService.getToken();
        ResponseEntity<String> response = registerWithToken(validToken);
        Assertions.assertEquals(200, response.getStatusCode().value(),
                "前置注册必须成功，但收到 HTTP " + response.getStatusCode()
                        + "，body=" + response.getBody());
        JSONObject body = JSON.parseObject(response.getBody());
        Assertions.assertEquals(200, body.getIntValue("code"),
                "前置注册必须业务成功，实际响应=" + response.getBody());
        Client client = clientService.findClientByToken(validToken);
        Assertions.assertNotNull(client, "前置注册后 ClientService 应能反查到 Client");
        return client;
    }

    /**
     * 对 /monitor/register 发起带 Authorization 头的 GET 请求。
     */
    private ResponseEntity<String> registerWithToken(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, token);
        return restTemplate.exchange(
                baseUrl() + "/monitor/register",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
    }

    /**
     * 调用 {@link AdminLoginSupport#loginAsAdmin(TestRestTemplate, String)} 做表单登录，返回 JWT。
     *
     * <p>调用前需在 {@code @BeforeEach} 已经 reset 过 admin 密码；本类的
     * {@link #resetIntegrationState()} 已经接管这一步。
     */
    private String loginAsAdmin() {
        return AdminLoginSupport.loginAsAdmin(restTemplate, baseUrl());
    }
}
