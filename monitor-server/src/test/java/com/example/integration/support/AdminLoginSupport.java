package com.example.integration.support;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * 集成测试统一登录辅助：把 Flyway V1 预置的 admin 行密码重置为已知值后做 form 登录，
 * 返回登录响应中的 JWT。
 *
 * <p>抽离自 PR2 {@code ClientRuntimeIT.loginAsAdmin} —— PR3 起的 {@code AlertFlowIT} /
 * {@code ProbeFlowIT} 都需要 admin JWT 调 {@code /api/alert/rule} 和 {@code /api/probes} 等仅
 * 管理员可访问的端点。复制三份会让任何一次密码 / 登录契约变更都得在三处同步修复，故抽离。
 *
 * <p>调用方在 {@code @BeforeEach} 中先调 {@link #resetAdminPassword(JdbcTemplate, PasswordEncoder)}
 * 把 BCrypt 哈希覆盖为 {@link #KNOWN_ADMIN_PASSWORD} 的运行时哈希——Flyway V1 预置的哈希明文
 * （{@code admin123}）虽然已被文档化在 {@code cleanup-after-test.sql} 注释里，但保持「测试自己设置密码」
 * 模式可以让 cleanup 脚本随时切回任意 BCrypt 哈希而不破坏 IT。
 */
public final class AdminLoginSupport {

    /**
     * 集成测试期间统一使用的 admin 明文密码：每次 {@link #resetAdminPassword(JdbcTemplate, PasswordEncoder)}
     * 把数据库里的 BCrypt 哈希覆盖为本常量的哈希；与 production admin 行为隔离。
     */
    public static final String KNOWN_ADMIN_PASSWORD = "monitor-it-admin-123";

    /**
     * Flyway V1 预置 admin 行的 id，{@code account.id = 1}；用作 Redis 频率限流 key
     * （{@code jwt:frequency:1}）。集成测试期间 admin 的 id 在 cleanup-after-test.sql 显式 INSERT
     * 为 1，故 hardcode 安全。
     */
    private static final int ADMIN_USER_ID = 1;

    /**
     * {@link com.example.utils.JwtUtils#frequencyCheck} 使用的 Redis key 前缀，与
     * {@link com.example.utils.Const#JWT_FREQUENCY} 同步。复制常量字符串避免测试包 import 主代码 utils
     * （维持单向依赖：测试 → 主代码 entity / config，不反过来）。
     */
    private static final String JWT_FREQUENCY_KEY_PREFIX = "jwt:frequency:";

    private AdminLoginSupport() {
    }

    /**
     * 重置 admin 行密码为 {@link #KNOWN_ADMIN_PASSWORD} 的运行时 BCrypt 哈希。
     *
     * <p>使用注入的 {@link PasswordEncoder} 走真实编码流程，与
     * {@code SecurityConfiguration} 装配的 encoder 一致；避免硬编码 BCrypt 哈希在测试间漂移。
     *
     * @param jdbcTemplate JDBC 模板（用于 UPDATE account 表）
     * @param encoder      Spring Security PasswordEncoder
     */
    public static void resetAdminPassword(JdbcTemplate jdbcTemplate, PasswordEncoder encoder) {
        String hashed = encoder.encode(KNOWN_ADMIN_PASSWORD);
        int updated = jdbcTemplate.update(
                "UPDATE account SET password = ? WHERE username = 'admin'", hashed);
        Assertions.assertEquals(1, updated,
                "Flyway V1 预置 admin 行应被 UPDATE，实际 updated=" + updated);
    }

    /**
     * 清掉 {@code jwt:frequency:1} Redis key，让下一次 {@code createJwt} 不会因频率限流被拒。
     *
     * <p>PR3 hotfix 背景：production yml 默认 {@code base=10s + frequency=30}，意味着同一 userId 在
     * 10 秒内做超过 30 次 createJwt 才会触发 upgrade-block。但 {@link com.example.utils.FlowUtils#internalCheck}
     * 的实现细节是：key 首次创建时返回 true（放行），key 已存在但未越过 frequency 阈值时也调用 action.run(false)
     * 返回 false（拒绝）—— 因 {@code limitOnceUpgradeCheck} 的 action 永远返回 false。结果是「同一 userId
     * 在 base TTL 内」仅允许首次登录，第 2+ 次都被拒。集成测试在 @BeforeEach 反复登录命中这条规则。
     *
     * <p>{@code application-it.yml} 已把 base 调到 1s 让 TTL 极短，但 CI runner 偶发卡顿 + 多个 IT
     * 类连跑仍可能踩坑。此处显式 DELETE Redis key 是兜底，保证「测试前的状态」绝对干净。
     */
    public static void clearLoginFrequencyLimit(StringRedisTemplate redisTemplate) {
        redisTemplate.delete(JWT_FREQUENCY_KEY_PREFIX + ADMIN_USER_ID);
    }

    /**
     * 重置 admin 密码并做表单登录，返回 JWT 字符串。一站式调用方便测试方法在 {@code @BeforeEach}
     * 之外按需登录。
     *
     * <p>{@code POST /api/auth/login} 接受 {@code application/x-www-form-urlencoded}（Spring
     * Security formLogin 默认），登录成功返回 {@code RestBean<AuthorizeVO>}，其中
     * {@code data.token} 为前端持久化的 JWT bearer。
     *
     * <p>PR3 hotfix：本重载内部调用 {@link #clearLoginFrequencyLimit(StringRedisTemplate)}
     * 在登录前清掉 Redis 频率 key，避免连续测试触发 {@code 登录验证频繁，请稍后再试} 误判。
     * 老调用方（如未注入 StringRedisTemplate 的早期 IT）可以继续走 {@link #resetAndLogin(JdbcTemplate, PasswordEncoder, TestRestTemplate, String)}
     * 重载（无 Redis 清理，依赖 application-it.yml base=1s TTL 兜底）。
     *
     * @param jdbcTemplate   JDBC 模板
     * @param encoder        Spring Security PasswordEncoder
     * @param redisTemplate  StringRedisTemplate，用于 DEL jwt:frequency:1 兜底
     * @param restTemplate   Spring Boot 测试 HTTP 客户端
     * @param baseUrl        被测服务的 baseUrl（如 {@code http://localhost:53210}）
     * @return JWT bearer 字符串（仅 token 字段，无 {@code Bearer } 前缀）
     */
    public static String resetAndLogin(JdbcTemplate jdbcTemplate,
                                       PasswordEncoder encoder,
                                       StringRedisTemplate redisTemplate,
                                       TestRestTemplate restTemplate,
                                       String baseUrl) {
        resetAdminPassword(jdbcTemplate, encoder);
        clearLoginFrequencyLimit(redisTemplate);
        return loginAsAdmin(restTemplate, baseUrl);
    }

    /**
     * 重置 admin 密码并做表单登录，返回 JWT 字符串。一站式调用方便测试方法在 {@code @BeforeEach}
     * 之外按需登录。
     *
     * <p>{@code POST /api/auth/login} 接受 {@code application/x-www-form-urlencoded}（Spring
     * Security formLogin 默认），登录成功返回 {@code RestBean<AuthorizeVO>}，其中
     * {@code data.token} 为前端持久化的 JWT bearer。
     *
     * <p>本重载不清理 Redis 频率 key，仅依赖 {@code application-it.yml} 的 {@code limit.base=1s}
     * TTL 兜底；推荐新调用方使用 {@link #resetAndLogin(JdbcTemplate, PasswordEncoder, StringRedisTemplate, TestRestTemplate, String)}
     * 重载主动清理 Redis key，避免 CI 卡顿期间偶发 403。
     *
     * @param jdbcTemplate JDBC 模板
     * @param encoder      Spring Security PasswordEncoder
     * @param restTemplate Spring Boot 测试 HTTP 客户端
     * @param baseUrl      被测服务的 baseUrl（如 {@code http://localhost:53210}）
     * @return JWT bearer 字符串（仅 token 字段，无 {@code Bearer } 前缀）
     */
    public static String resetAndLogin(JdbcTemplate jdbcTemplate,
                                       PasswordEncoder encoder,
                                       TestRestTemplate restTemplate,
                                       String baseUrl) {
        resetAdminPassword(jdbcTemplate, encoder);
        return loginAsAdmin(restTemplate, baseUrl);
    }

    /**
     * 用已知密码做表单登录。调用前需保证 {@link #resetAdminPassword(JdbcTemplate, PasswordEncoder)}
     * 已被调用过，否则 BCrypt 校验会失败返回 401。
     *
     * @param restTemplate Spring Boot 测试 HTTP 客户端
     * @param baseUrl      被测服务的 baseUrl
     * @return JWT bearer 字符串
     */
    public static String loginAsAdmin(TestRestTemplate restTemplate, String baseUrl) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", "admin");
        form.add("password", KNOWN_ADMIN_PASSWORD);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<String> response = restTemplate.exchange(
                baseUrl + "/api/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(form, headers),
                String.class);
        Assertions.assertEquals(200, response.getStatusCode().value(),
                "/api/auth/login 应返回 HTTP 200，实际=" + response.getStatusCode()
                        + ", body=" + response.getBody());
        JSONObject body = JSON.parseObject(response.getBody());
        Assertions.assertNotNull(body, "/api/auth/login 响应不应为空");
        Assertions.assertEquals(200, body.getIntValue("code"),
                "/api/auth/login RestBean.code 应为 200，实际=" + body);
        JSONObject data = body.getJSONObject("data");
        Assertions.assertNotNull(data, "/api/auth/login data 字段不应为空，实际=" + body);
        String token = data.getString("token");
        Assertions.assertNotNull(token, "/api/auth/login 必须返回非空 token，实际 data=" + data);
        return token;
    }
}
