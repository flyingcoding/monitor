package com.example.service.impl.probe;

import com.example.entity.dto.ProbeTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * HTTP(S) 探测执行器。
 *
 * <p>支持：
 * <ul>
 *   <li>Custom Headers 与 Basic Auth（已由 Service 层解密后传入）；</li>
 *   <li>状态码校验：默认 2xx，{@link ProbeTask#getExpectedStatusCode()} 配置时严格匹配；</li>
 *   <li>响应体正则匹配（{@link ProbeTask#getExpectedBodyPattern()}）；</li>
 *   <li>HTTPS 证书剩余天数检测（通过 SSL handshake 取 server cert chain 的 notAfter）。</li>
 * </ul>
 *
 * <p>使用 Java 11+ 的 {@link HttpClient}（HTTP/1.1，{@code redirect=NEVER} 避免 SSL 跳转污染证书读取）。
 * SSL 信息：JDK 21 的 {@link java.net.http.HttpResponse#sslSession()} 是 {@code Optional<SSLSession>}，
 * 可直接拿 peer cert chain；但当目标是 {@code http://} 时则不返回任何信息。
 */
@Slf4j
@Component
public class HttpProbeExecutor implements ProbeExecutor {

    @Override
    public ProbeResult execute(ProbeTask task,
                               Map<String, String> decryptedHeaders,
                               String decryptedBasicPwd) {
        if (task == null) {
            return ProbeResult.builder()
                    .success(false)
                    .errorMessage("task 为空")
                    .build();
        }
        String url = task.getTarget();
        int timeoutSec = task.getTimeoutSec() == null ? 10 : task.getTimeoutSec();
        long start = System.currentTimeMillis();

        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(timeoutSec));

            // 应用 Custom Headers
            if (decryptedHeaders != null) {
                for (Map.Entry<String, String> entry : decryptedHeaders.entrySet()) {
                    String key = entry.getKey();
                    String value = entry.getValue();
                    if (key == null || value == null) {
                        continue;
                    }
                    try {
                        requestBuilder.header(key, value);
                    } catch (IllegalArgumentException ex) {
                        log.debug("跳过非法 Header key={} reason={}", key, ex.getMessage());
                    }
                }
            }

            // Basic Auth：username + password 同时存在时拼 Authorization 头
            if (task.getBasicAuthUsername() != null && !task.getBasicAuthUsername().isBlank()
                    && decryptedBasicPwd != null && !decryptedBasicPwd.isEmpty()) {
                String token = task.getBasicAuthUsername() + ":" + decryptedBasicPwd;
                String encoded = Base64.getEncoder()
                        .encodeToString(token.getBytes(StandardCharsets.UTF_8));
                requestBuilder.header("Authorization", "Basic " + encoded);
            }

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeoutSec))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

            HttpResponse<String> response = client.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            int latency = (int) (System.currentTimeMillis() - start);
            int statusCode = response.statusCode();

            // SSL 证书剩余天数（仅 HTTPS）
            Integer sslDays = null;
            boolean sslExpiring = false;
            int sslWarnDays = task.getSslWarnDays() == null ? 30 : task.getSslWarnDays();
            if (url.startsWith("https://")) {
                Optional<SSLSession> session = response.sslSession();
                if (session.isPresent()) {
                    sslDays = extractSslDaysRemaining(session.get());
                    if (sslDays != null && sslDays <= sslWarnDays) {
                        sslExpiring = true;
                    }
                }
            }

            // 期望状态码校验
            Integer expected = task.getExpectedStatusCode();
            boolean statusOk = (expected == null)
                    ? (statusCode >= 200 && statusCode < 300)
                    : (statusCode == expected);

            // 期望响应体正则校验
            boolean bodyOk = true;
            String bodyError = null;
            if (task.getExpectedBodyPattern() != null && !task.getExpectedBodyPattern().isBlank()) {
                try {
                    Pattern pattern = Pattern.compile(task.getExpectedBodyPattern());
                    String body = response.body() == null ? "" : response.body();
                    bodyOk = pattern.matcher(body).find();
                    if (!bodyOk) {
                        bodyError = "响应体未匹配期望模式";
                    }
                } catch (PatternSyntaxException ex) {
                    bodyOk = false;
                    bodyError = "expected_body_pattern 正则不合法";
                }
            }

            boolean overallOk = statusOk && bodyOk;
            String errorMsg = null;
            if (!statusOk) {
                errorMsg = expected == null
                        ? "HTTP 状态码 " + statusCode + " 非 2xx"
                        : "HTTP 状态码 " + statusCode + " 不等于期望 " + expected;
            } else if (!bodyOk) {
                errorMsg = bodyError;
            }

            return ProbeResult.builder()
                    .success(overallOk)
                    .latencyMs(latency)
                    .statusCode(statusCode)
                    .sslDaysRemaining(sslDays)
                    .sslExpiringSoon(sslExpiring)
                    .errorMessage(errorMsg)
                    .build();
        } catch (java.net.http.HttpTimeoutException ex) {
            int latency = (int) (System.currentTimeMillis() - start);
            return ProbeResult.builder()
                    .success(false)
                    .latencyMs(latency)
                    .errorMessage("HTTP 探测超时")
                    .build();
        } catch (Exception ex) {
            int latency = (int) (System.currentTimeMillis() - start);
            String msg = ex.getMessage();
            if (msg == null) {
                msg = ex.getClass().getSimpleName();
            }
            if (msg.length() > 256) {
                msg = msg.substring(0, 256);
            }
            return ProbeResult.builder()
                    .success(false)
                    .latencyMs(latency)
                    .errorMessage(msg)
                    .build();
        }
    }

    /**
     * 从 SSL session 提取首张 server 证书的剩余有效天数。
     *
     * @param session JDK HttpClient 暴露的 SSL Session
     * @return 剩余天数（>=0），获取失败返回 null
     */
    private Integer extractSslDaysRemaining(SSLSession session) {
        try {
            Certificate[] chain = session.getPeerCertificates();
            if (chain == null || chain.length == 0) {
                return null;
            }
            Certificate first = chain[0];
            if (!(first instanceof X509Certificate x509)) {
                return null;
            }
            Instant notAfter = x509.getNotAfter().toInstant();
            long days = ChronoUnit.DAYS.between(Instant.now(), notAfter);
            return (int) Math.max(0L, days);
        } catch (Exception e) {
            log.debug("解析 SSL 证书剩余天数失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 直接通过 HttpsURLConnection 拉取证书的旁路；保留以备 sslSession() 不可用时调试，
     * 当前主流程不使用（{@code java.net.http.HttpClient} 已经给出 SSLSession）。
     */
    @SuppressWarnings("unused")
    private Integer extractSslDaysRemainingByUrlConnection(String url) {
        try {
            URL u = new URL(url);
            HttpsURLConnection conn = (HttpsURLConnection) u.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.connect();
            Certificate[] chain = conn.getServerCertificates();
            conn.disconnect();
            if (chain == null || chain.length == 0) {
                return null;
            }
            if (!(chain[0] instanceof X509Certificate x509)) {
                return null;
            }
            long days = ChronoUnit.DAYS.between(Instant.now(), x509.getNotAfter().toInstant());
            return (int) Math.max(0L, days);
        } catch (Exception e) {
            return null;
        }
    }
}
