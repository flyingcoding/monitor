package com.example.controller;

import com.example.entity.dto.Account;
import com.example.entity.vo.response.RuntimeHistoryVO;
import com.example.service.AccountService;
import com.example.service.ClientService;
import com.example.service.PermissionService;
import com.example.utils.Const;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link MonitorController} 单元测试，专注 PR1 引入的 {@code GET /api/monitor/runtime_history}
 * from/to 参数与时间窗口校验。
 *
 * <p>沿用项目惯例：JDK 动态代理桩 ClientService，真实 PermissionService 用以走 admin 权限分支。
 * 不依赖 Mockito，避免 JDK strict-access 模式下 attach 问题。
 */
class MonitorControllerTest {

    private MockMvc mockMvc;
    private final AtomicReference<RuntimeHistoryVO> stubResponse = new AtomicReference<>();
    private final AtomicReference<Instant> capturedFrom = new AtomicReference<>();
    private final AtomicReference<Instant> capturedTo = new AtomicReference<>();
    private final AtomicReference<Integer> capturedClientId = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        stubResponse.set(new RuntimeHistoryVO());
        capturedFrom.set(null);
        capturedTo.set(null);
        capturedClientId.set(null);

        MonitorController controller = new MonitorController();

        ClientService clientService = (ClientService) Proxy.newProxyInstance(
                ClientService.class.getClassLoader(),
                new Class[]{ClientService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "clientRuntimeDetailsHistory" -> {
                        capturedClientId.set((Integer) args[0]);
                        capturedFrom.set((Instant) args[1]);
                        capturedTo.set((Instant) args[2]);
                        yield stubResponse.get();
                    }
                    case "toString" -> "ClientServiceStub";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException("测试桩未实现方法: " + method.getName());
                });

        // PermissionService 与项目其他 controller test 保持一致：用真实实例 + AccountService 桩
        AccountService accountService = (AccountService) Proxy.newProxyInstance(
                AccountService.class.getClassLoader(),
                new Class[]{AccountService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getById" -> {
                        // 默认子账户只能访问 1001（用于权限分支测试）；admin 用例不走此路径
                        yield new Account(7, "u1", "p", "u1@test.com", "user", "[1001]", null, Boolean.TRUE);
                    }
                    case "toString" -> "AccountServiceStub";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException("测试桩未实现方法: " + method.getName());
                });

        PermissionService permissionService = new PermissionService();
        ReflectionTestUtils.setField(permissionService, "accountService", accountService);

        ReflectionTestUtils.setField(controller, "clientService", clientService);
        ReflectionTestUtils.setField(controller, "permissionService", permissionService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /**
     * 缺省 from/to 应回退到最近 1 小时窗口，保持 v2.0-beta 之前的行为以兼容老前端。
     */
    @Test
    void runtimeHistoryWithoutFromOrToShouldDefaultToLastOneHourWindow() throws Exception {
        Instant before = Instant.now();
        mockMvc.perform(get("/api/monitor/runtime_history")
                        .param("clientId", "42")
                        .requestAttr(Const.ATTR_USER_ID, 100)
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        Instant after = Instant.now();

        Instant from = capturedFrom.get();
        Instant to = capturedTo.get();
        assertNotNull(from, "缺省 from 应由 server 端补齐");
        assertNotNull(to, "缺省 to 应由 server 端补齐");
        Duration delta = Duration.between(from, to);
        assertTrue(Math.abs(delta.toMinutes() - 60) <= 1,
                "缺省窗口应为 1h，实际 " + delta);
        // 截止时间应在请求处理时间窗口内（now ± 1s）
        assertTrue(!to.isBefore(before.minusSeconds(1)) && !to.isAfter(after.plusSeconds(1)),
                "缺省 to 应贴近 now");
    }

    /**
     * 显式 from/to 应直接透传到 service 层。
     */
    @Test
    void runtimeHistoryWithExplicitFromAndToShouldForwardToService() throws Exception {
        // 用过去 30 分钟窗口（合法 ≤ 7d）
        Instant to = Instant.now();
        Instant from = to.minusSeconds(1800);
        mockMvc.perform(get("/api/monitor/runtime_history")
                        .param("clientId", "42")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .requestAttr(Const.ATTR_USER_ID, 100)
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 时间序列化可能有微小精度差，按毫秒级断言
        assertNotNull(capturedFrom.get());
        assertNotNull(capturedTo.get());
        assertTrue(Math.abs(Duration.between(from, capturedFrom.get()).toMillis()) < 100,
                "from 应被透传到 service");
        assertTrue(Math.abs(Duration.between(to, capturedTo.get()).toMillis()) < 100,
                "to 应被透传到 service");
        assertNotNull(capturedClientId.get());
    }

    /**
     * from > to 必须返回 400。
     */
    @Test
    void runtimeHistoryWithFromAfterToShouldReturnBadRequest() throws Exception {
        Instant now = Instant.now();
        Instant from = now;
        Instant to = now.minusSeconds(60);
        mockMvc.perform(get("/api/monitor/runtime_history")
                        .param("clientId", "42")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .requestAttr(Const.ATTR_USER_ID, 100)
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin"))
                .andExpect(status().isBadRequest());
    }

    /**
     * from == to 也应返回 400（必须严格早于）。
     */
    @Test
    void runtimeHistoryWithFromEqualToToShouldReturnBadRequest() throws Exception {
        Instant now = Instant.now();
        mockMvc.perform(get("/api/monitor/runtime_history")
                        .param("clientId", "42")
                        .param("from", now.toString())
                        .param("to", now.toString())
                        .requestAttr(Const.ATTR_USER_ID, 100)
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin"))
                .andExpect(status().isBadRequest());
    }

    /**
     * 时间跨度 > 7 天必须返回 400。
     */
    @Test
    void runtimeHistoryWithWindowExceedingSevenDaysShouldReturnBadRequest() throws Exception {
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(7)).minusSeconds(1);
        mockMvc.perform(get("/api/monitor/runtime_history")
                        .param("clientId", "42")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .requestAttr(Const.ATTR_USER_ID, 100)
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin"))
                .andExpect(status().isBadRequest());
    }

    /**
     * 时间跨度等于 7 天恰好允许（≤ 7d 边界）。
     */
    @Test
    void runtimeHistoryWithExactlySevenDaysShouldBeAllowed() throws Exception {
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(7));
        mockMvc.perform(get("/api/monitor/runtime_history")
                        .param("clientId", "42")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .requestAttr(Const.ATTR_USER_ID, 100)
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 越权子账户访问其他客户端应返回 noPermission（HTTP 200 + code 401，复用项目契约）。
     */
    @Test
    void runtimeHistoryShouldRejectOutOfScopeClient() throws Exception {
        // 子账户 7 只能访问 1001，请求 1002 应被拒
        mockMvc.perform(get("/api/monitor/runtime_history")
                        .param("clientId", "1002")
                        .requestAttr(Const.ATTR_USER_ID, 7)
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    /**
     * 子账户在自己可见范围内可正常查询（包含缺省 from/to 路径）。
     */
    @Test
    void runtimeHistoryAllowsSubAccountWithinAllowedClients() throws Exception {
        mockMvc.perform(get("/api/monitor/runtime_history")
                        .param("clientId", "1001")
                        .requestAttr(Const.ATTR_USER_ID, 7)
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertNotNull(capturedFrom.get(), "子账户合法查询应进入 service 层");
        assertNotNull(capturedTo.get());
    }

    /**
     * 前端 {@code Date.prototype.toISOString()} 产出形如 {@code 2026-05-20T00:00:00.123Z}
     * 的 ISO 8601 字符串（带毫秒、Z 后缀的 UTC），后端必须能用 {@code @DateTimeFormat(ISO.DATE_TIME)}
     * 解析为 {@link Instant} 而不丢失精度或时区。
     *
     * <p>该测试锁定前后端契约：前端可以用 {@code new Date(...).toISOString()} 直接拼到 URLSearchParams，
     * 后端解析到完全相等的 Instant 值。
     */
    @Test
    void runtimeHistoryShouldParseFrontendIsoStringWithMillisecondsAndUtcZ() throws Exception {
        // 模拟前端 new Date('2026-05-20T00:00:00Z').toISOString() 输出
        String fromStr = "2026-05-20T00:00:00.123Z";
        String toStr = "2026-05-20T00:30:00.456Z";
        mockMvc.perform(get("/api/monitor/runtime_history")
                        .param("clientId", "42")
                        .param("from", fromStr)
                        .param("to", toStr)
                        .requestAttr(Const.ATTR_USER_ID, 100)
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertNotNull(capturedFrom.get(), "带毫秒的 ISO 字符串必须能被解析");
        assertNotNull(capturedTo.get());
        Assertions.assertEquals(Instant.parse(fromStr), capturedFrom.get(),
                "前端 toISOString() 带毫秒的形式应原样还原为 Instant");
        Assertions.assertEquals(Instant.parse(toStr), capturedTo.get(),
                "前端 toISOString() 带毫秒的形式应原样还原为 Instant");
    }
}
