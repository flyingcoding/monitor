package com.example.controller;

import com.example.entity.dto.AlertRule;
import com.example.mapper.struct.AlertStructMapper;
import com.example.mapper.struct.AlertStructMapperImpl;
import com.example.service.AlertRuleService;
import com.example.service.PermissionService;
import com.example.utils.Const;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AlertRuleControllerTest {

    private MockMvc mockMvc;
    private final Map<Long, AlertRule> ruleStore = new HashMap<>();
    private final AtomicLong idSeq = new AtomicLong(0);
    private final AtomicReference<List<AlertRule>> lastListResult = new AtomicReference<>(new ArrayList<>());

    /**
     * 构建独立 Controller 测试上下文：手动注入 MapStruct 实现、Proxy 桩化 Service / 权限组件，
     * 维持与项目现有 ClientControllerTest 一致的轻量风格，避免依赖 JVM attach 的 Mockito。
     */
    @BeforeEach
    void setUp() {
        ruleStore.clear();
        idSeq.set(0);
        AlertRuleController controller = new AlertRuleController();

        AlertRuleService alertRuleService = (AlertRuleService) Proxy.newProxyInstance(
                AlertRuleService.class.getClassLoader(),
                new Class[]{AlertRuleService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getById" -> ruleStore.get(((Number) args[0]).longValue());
                    case "save" -> {
                        AlertRule rule = (AlertRule) args[0];
                        long id = idSeq.incrementAndGet();
                        rule.setId(id);
                        rule.setCreatedAt(new Date());
                        rule.setUpdatedAt(new Date());
                        ruleStore.put(id, rule);
                        yield true;
                    }
                    case "updateById" -> {
                        AlertRule rule = (AlertRule) args[0];
                        rule.setUpdatedAt(new Date());
                        ruleStore.put(rule.getId(), rule);
                        yield true;
                    }
                    case "removeById" -> {
                        Object arg = args[0];
                        Long id = (arg instanceof Number n) ? n.longValue() : null;
                        yield id != null && ruleStore.remove(id) != null;
                    }
                    case "list" -> {
                        // 简化：忽略 wrapper，返回全部并保留排序
                        List<AlertRule> all = new ArrayList<>(ruleStore.values());
                        all.sort((a, b) -> Long.compare(b.getId(), a.getId()));
                        lastListResult.set(all);
                        yield all;
                    }
                    case "listByIds" -> {
                        Collection<?> ids = (Collection<?>) args[0];
                        List<AlertRule> out = new ArrayList<>();
                        for (Object idObj : ids) {
                            long id = ((Number) idObj).longValue();
                            AlertRule r = ruleStore.get(id);
                            if (r != null) out.add(r);
                        }
                        yield out;
                    }
                    case "toString" -> "AlertRuleServiceStub";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException("测试桩未实现方法: " + method.getName());
                });

        PermissionService permissionService = new PermissionService();
        AlertStructMapper alertStructMapper = new AlertStructMapperImpl();

        ReflectionTestUtils.setField(controller, "alertRuleService", alertRuleService);
        ReflectionTestUtils.setField(controller, "permissionService", permissionService);
        ReflectionTestUtils.setField(controller, "alertStructMapper", alertStructMapper);

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /**
     * 非管理员访问规则列表应返回 401 拒绝。
     */
    @Test
    void listShouldRejectNonAdmin() throws Exception {
        mockMvc.perform(get("/api/alert/rule").requestAttr(Const.ATTR_USER_ROLE, "ROLE_user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    /**
     * 管理员创建告警规则后返回 200 且 data 中含 id。
     */
    @Test
    void createShouldReturnSuccessAndAssignedId() throws Exception {
        String payload = """
                {
                  "name": "CPU高",
                  "metric": "cpu",
                  "operator": "gt",
                  "threshold": 80,
                  "durationSec": 60,
                  "level": "warning",
                  "enabled": true,
                  "channelIds": [1, 2]
                }
                """;
        mockMvc.perform(post("/api/alert/rule")
                        .contentType("application/json")
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.metric").value("cpu"));
        assertEquals(1, ruleStore.size());
    }

    /**
     * 非管理员尝试创建规则返回 401。
     */
    @Test
    void createShouldRejectNonAdmin() throws Exception {
        String payload = """
                {
                  "name": "CPU高",
                  "metric": "cpu",
                  "operator": "gt",
                  "threshold": 80,
                  "durationSec": 60,
                  "level": "warning",
                  "enabled": true
                }
                """;
        mockMvc.perform(post("/api/alert/rule")
                        .contentType("application/json")
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_user")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
        assertEquals(0, ruleStore.size());
    }

    /**
     * 阈值越界请求应返回 400。
     */
    @Test
    void createShouldRejectInvalidThreshold() throws Exception {
        String payload = """
                {
                  "name": "无效阈值",
                  "metric": "cpu",
                  "operator": "gt",
                  "threshold": -1,
                  "durationSec": 60,
                  "level": "warning",
                  "enabled": true
                }
                """;
        mockMvc.perform(post("/api/alert/rule")
                        .contentType("application/json")
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin")
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    /**
     * 更新已存在规则应返回最新字段。
     */
    @Test
    void updateShouldPersistChanges() throws Exception {
        AlertRule rule = new AlertRule();
        rule.setName("旧名称");
        rule.setMetric("cpu");
        rule.setOperator("gt");
        rule.setThreshold(70.0);
        rule.setDurationSec(60);
        rule.setLevel("warning");
        rule.setEnabled(true);
        idSeq.set(0);
        long id = idSeq.incrementAndGet();
        rule.setId(id);
        rule.setCreatedAt(new Date());
        rule.setUpdatedAt(new Date());
        ruleStore.put(id, rule);

        String payload = """
                {
                  "name": "新名称",
                  "metric": "memory",
                  "operator": "gte",
                  "threshold": 95,
                  "durationSec": 120,
                  "level": "critical",
                  "enabled": false
                }
                """;
        mockMvc.perform(put("/api/alert/rule/" + id)
                        .contentType("application/json")
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("新名称"))
                .andExpect(jsonPath("$.data.metric").value("memory"))
                .andExpect(jsonPath("$.data.level").value("critical"));
    }

    /**
     * 更新不存在的规则应返回 404。
     */
    @Test
    void updateShouldReturnNotFoundWhenRuleMissing() throws Exception {
        String payload = """
                {
                  "name": "n",
                  "metric": "cpu",
                  "operator": "gt",
                  "threshold": 80,
                  "durationSec": 60,
                  "level": "warning",
                  "enabled": true
                }
                """;
        mockMvc.perform(put("/api/alert/rule/999")
                        .contentType("application/json")
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin")
                        .content(payload))
                .andExpect(status().isNotFound());
    }

    /**
     * 删除已存在规则返回 200。
     */
    @Test
    void deleteShouldReturnSuccess() throws Exception {
        AlertRule rule = new AlertRule();
        rule.setId(1L);
        rule.setName("del");
        rule.setMetric("cpu");
        rule.setOperator("gt");
        rule.setThreshold(80.0);
        rule.setDurationSec(60);
        rule.setLevel("warning");
        rule.setEnabled(true);
        rule.setCreatedAt(new Date());
        rule.setUpdatedAt(new Date());
        ruleStore.put(1L, rule);

        mockMvc.perform(delete("/api/alert/rule/1")
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(0, ruleStore.size());
    }

    /**
     * 删除不存在规则返回 404。
     */
    @Test
    void deleteShouldReturnNotFoundWhenMissing() throws Exception {
        mockMvc.perform(delete("/api/alert/rule/123")
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin"))
                .andExpect(status().isNotFound());
    }

    /**
     * 静默操作应设置 silence_until 字段。
     */
    @Test
    void silenceShouldSetSilenceUntil() throws Exception {
        AlertRule rule = new AlertRule();
        rule.setId(1L);
        rule.setName("s");
        rule.setMetric("cpu");
        rule.setOperator("gt");
        rule.setThreshold(80.0);
        rule.setDurationSec(60);
        rule.setLevel("warning");
        rule.setEnabled(true);
        rule.setCreatedAt(new Date());
        rule.setUpdatedAt(new Date());
        ruleStore.put(1L, rule);

        long before = System.currentTimeMillis();
        mockMvc.perform(post("/api/alert/rule/1/silence?minutes=5")
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.silenceUntil").exists());

        AlertRule reloaded = ruleStore.get(1L);
        assertNotNull(reloaded.getSilenceUntil());
        long delta = reloaded.getSilenceUntil().getTime() - before;
        // 期望约 5 分钟（300000ms），允许一定误差
        assertEquals(true, delta >= 299_000 && delta <= 305_000,
                "silence_until 应约等于 now + 5min，但实际间隔为 " + delta + "ms");
    }

    /**
     * 静默时长越界（0 分钟）应返回 400。
     */
    @Test
    void silenceShouldRejectInvalidMinutes() throws Exception {
        AlertRule rule = new AlertRule();
        rule.setId(1L);
        rule.setName("s");
        rule.setMetric("cpu");
        rule.setOperator("gt");
        rule.setThreshold(80.0);
        rule.setDurationSec(60);
        rule.setLevel("warning");
        rule.setEnabled(true);
        rule.setCreatedAt(new Date());
        rule.setUpdatedAt(new Date());
        ruleStore.put(1L, rule);

        mockMvc.perform(post("/api/alert/rule/1/silence?minutes=0")
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin"))
                .andExpect(status().isBadRequest());
    }
}
