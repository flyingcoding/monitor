package com.example.service.impl.probe;

import com.example.entity.dto.ProbeTask;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link HttpProbeExecutor} 单元测试。使用 JDK {@code com.sun.net.httpserver.HttpServer} 作为
 * 测试目标，避免对外网络依赖。
 */
class HttpProbeExecutorTest {

    private HttpServer server;
    private int port;
    private final HttpProbeExecutor executor = new HttpProbeExecutor();
    private final AtomicReference<Map<String, String>> capturedHeaders = new AtomicReference<>();
    private final AtomicReference<Integer> respondStatus = new AtomicReference<>(200);
    private final AtomicReference<String> respondBody = new AtomicReference<>("ok");

    @BeforeEach
    void startServer() throws IOException {
        capturedHeaders.set(new LinkedHashMap<>());
        respondStatus.set(200);
        respondBody.set("ok");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            // 捕获请求头
            Map<String, String> h = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> h.put(k, v == null || v.isEmpty() ? "" : v.get(0)));
            capturedHeaders.set(h);
            String body = respondBody.get() == null ? "" : respondBody.get();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(respondStatus.get(), bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldSucceedOn2xx() {
        ProbeTask t = task("http://127.0.0.1:" + port + "/health");
        ProbeResult result = executor.execute(t, Map.of(), null);
        Assertions.assertTrue(result.isSuccess());
        Assertions.assertEquals(200, result.getStatusCode());
        Assertions.assertNotNull(result.getLatencyMs());
        Assertions.assertNull(result.getErrorMessage());
    }

    @Test
    void shouldFailOnNon2xxWhenNoExpectedStatus() {
        respondStatus.set(500);
        ProbeTask t = task("http://127.0.0.1:" + port + "/health");
        ProbeResult result = executor.execute(t, Map.of(), null);
        Assertions.assertFalse(result.isSuccess());
        Assertions.assertEquals(500, result.getStatusCode());
        Assertions.assertTrue(result.getErrorMessage().contains("500"));
    }

    @Test
    void shouldHonorExpectedStatusCode() {
        respondStatus.set(204);
        ProbeTask t = task("http://127.0.0.1:" + port + "/health");
        t.setExpectedStatusCode(204);
        ProbeResult result = executor.execute(t, Map.of(), null);
        Assertions.assertTrue(result.isSuccess());
        Assertions.assertEquals(204, result.getStatusCode());

        // 实际返回 204 但期望 200 → 不匹配
        t.setExpectedStatusCode(200);
        result = executor.execute(t, Map.of(), null);
        Assertions.assertFalse(result.isSuccess());
    }

    @Test
    void shouldAddCustomHeaders() {
        ProbeTask t = task("http://127.0.0.1:" + port + "/health");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Probe-Token", "abc-123");
        ProbeResult result = executor.execute(t, headers, null);
        Assertions.assertTrue(result.isSuccess());

        Map<String, String> seen = capturedHeaders.get();
        Assertions.assertEquals("abc-123", seen.get("X-probe-token"),
                "Custom Header 应在请求时附加（Header key 大小写不敏感）");
    }

    @Test
    void shouldAddBasicAuthHeader() {
        ProbeTask t = task("http://127.0.0.1:" + port + "/health");
        t.setBasicAuthUsername("alice");
        ProbeResult result = executor.execute(t, Map.of(), "p@ssw0rd");
        Assertions.assertTrue(result.isSuccess());

        Map<String, String> seen = capturedHeaders.get();
        String auth = seen.get("Authorization");
        Assertions.assertNotNull(auth, "应包含 Authorization 头");
        Assertions.assertTrue(auth.startsWith("Basic "));
        String decoded = new String(Base64.getDecoder().decode(auth.substring("Basic ".length())),
                StandardCharsets.UTF_8);
        Assertions.assertEquals("alice:p@ssw0rd", decoded);
    }

    @Test
    void shouldValidateExpectedBodyPattern() {
        respondBody.set("status: healthy\nversion: 1.0");
        ProbeTask t = task("http://127.0.0.1:" + port + "/health");
        t.setExpectedBodyPattern("status:\\s*healthy");
        ProbeResult result = executor.execute(t, Map.of(), null);
        Assertions.assertTrue(result.isSuccess());

        t.setExpectedBodyPattern("status:\\s*degraded");
        result = executor.execute(t, Map.of(), null);
        Assertions.assertFalse(result.isSuccess());
        Assertions.assertNotNull(result.getErrorMessage());
    }

    @Test
    void shouldReturnFailureOnNullTask() {
        ProbeResult result = executor.execute(null, Map.of(), null);
        Assertions.assertFalse(result.isSuccess());
    }

    @Test
    void shouldReturnFailureOnUnreachableUrl() {
        ProbeTask t = task("http://127.0.0.1:1");
        t.setTimeoutSec(1);
        ProbeResult result = executor.execute(t, Map.of(), null);
        Assertions.assertFalse(result.isSuccess());
        Assertions.assertNotNull(result.getErrorMessage());
    }

    @Test
    void shouldSurfaceInvalidPatternAsFailure() {
        respondBody.set("anything");
        ProbeTask t = task("http://127.0.0.1:" + port + "/health");
        t.setExpectedBodyPattern("([0-9"); // 非法正则
        ProbeResult result = executor.execute(t, Map.of(), null);
        Assertions.assertFalse(result.isSuccess());
        Assertions.assertTrue(result.getErrorMessage().contains("正则"));
    }

    private ProbeTask task(String target) {
        ProbeTask t = new ProbeTask();
        t.setId(1L);
        t.setName("http-test");
        t.setType("http");
        t.setTarget(target);
        t.setIntervalSec(60);
        t.setTimeoutSec(10);
        t.setSslWarnDays(30);
        t.setConsecutiveFailuresThreshold(2);
        t.setEnabled(Boolean.TRUE);
        return t;
    }
}
