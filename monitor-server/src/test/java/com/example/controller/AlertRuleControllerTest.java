package com.example.controller;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.example.entity.alert.AlertStatus;
import com.example.entity.dto.AlertHistory;
import com.example.entity.dto.AlertRule;
import com.example.mapper.struct.AlertStructMapper;
import com.example.mapper.struct.AlertStructMapperImpl;
import com.example.service.AlertRuleService;
import com.example.service.PermissionService;
import com.example.service.impl.AlertWindowCache;
import com.example.utils.Const;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AlertRuleControllerTest {

    private MockMvc mockMvc;
    private final Map<Long, AlertRule> ruleStore = new HashMap<>();
    private final Map<Long, AlertHistory> historyStore = new HashMap<>();
    private final AtomicLong idSeq = new AtomicLong(0);
    private final AtomicLong historyIdSeq = new AtomicLong(0);
    private final AtomicReference<List<AlertRule>> lastListResult = new AtomicReference<>(new ArrayList<>());
    private AlertWindowCache alertWindowCache;

    /**
     * 构建独立 Controller 测试上下文：手动注入 MapStruct 实现、Proxy 桩化 Service / 权限组件，
     * 维持与项目现有 ClientControllerTest 一致的轻量风格，避免依赖 JVM attach 的 Mockito。
     */
    @BeforeEach
    void setUp() {
        ruleStore.clear();
        historyStore.clear();
        idSeq.set(0);
        historyIdSeq.set(0);
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
                    case "resolveActivesByRule" -> {
                        // 真实路径模拟：扫描 historyStore，把 firing / acknowledged 的告警改成 resolved，
                        // 这样 Controller 的"变更触发 resolve"逻辑能被端到端断言。
                        Long ruleId = (Long) args[0];
                        Integer onlyClientId = (Integer) args[1];
                        String reason = (String) args[2];
                        Set<String> active = Set.of(
                                AlertStatus.FIRING.getColumn(),
                                AlertStatus.ACKNOWLEDGED.getColumn());
                        int affected = 0;
                        Date now = new Date();
                        String suffix = reason == null || reason.isBlank() ? "" : "（" + reason + "）";
                        for (AlertHistory h : historyStore.values()) {
                            if (!java.util.Objects.equals(h.getRuleId(), ruleId)) continue;
                            if (!active.contains(h.getStatus())) continue;
                            if (onlyClientId != null && !java.util.Objects.equals(h.getClientId(), onlyClientId)) continue;
                            h.setStatus(AlertStatus.RESOLVED.getColumn());
                            h.setResolvedAt(now);
                            String original = h.getMessage() == null ? "" : h.getMessage();
                            if (!suffix.isEmpty()) {
                                h.setMessage(original + suffix);
                            }
                            affected++;
                        }
                        yield affected;
                    }
                    case "toString" -> "AlertRuleServiceStub";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException("测试桩未实现方法: " + method.getName());
                });

        PermissionService permissionService = new PermissionService();
        AlertStructMapper alertStructMapper = new AlertStructMapperImpl();
        alertWindowCache = new AlertWindowCache();

        ReflectionTestUtils.setField(controller, "alertRuleService", alertRuleService);
        ReflectionTestUtils.setField(controller, "permissionService", permissionService);
        ReflectionTestUtils.setField(controller, "alertStructMapper", alertStructMapper);
        ReflectionTestUtils.setField(controller, "alertWindowCache", alertWindowCache);

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /**
     * 测试辅助：直接放一条规则到 ruleStore，绕过 controller create 路径。
     */
    private AlertRule seedRule(long id, Integer clientId, boolean enabled,
                               String metric, String operator, double threshold, int durationSec) {
        AlertRule rule = new AlertRule();
        rule.setId(id);
        rule.setName("r" + id);
        rule.setClientId(clientId);
        rule.setMetric(metric);
        rule.setOperator(operator);
        rule.setThreshold(threshold);
        rule.setDurationSec(durationSec);
        rule.setLevel("warning");
        rule.setEnabled(enabled);
        rule.setCreatedAt(new Date());
        rule.setUpdatedAt(new Date());
        ruleStore.put(id, rule);
        return rule;
    }

    /**
     * 测试辅助：放一条活跃告警历史（默认 firing）。
     */
    private AlertHistory seedHistory(long ruleId, Integer clientId, String status) {
        AlertHistory h = new AlertHistory();
        long id = historyIdSeq.incrementAndGet();
        h.setId(id);
        h.setRuleId(ruleId);
        h.setClientId(clientId);
        h.setStatus(status);
        h.setLevel("warning");
        h.setFiredAt(new Date(System.currentTimeMillis() - 60_000));
        h.setMessage("旧告警");
        historyStore.put(id, h);
        return h;
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

    /**
     * 回归：更新规则（编辑、启停开关）不应清空 silenceUntil。
     * <p>
     * 修复点（参见 AlertRuleUpdateVO + AlertStructMapper.updateRule 的 ignore 注解）：
     * 之前 update 把 vo.silenceUntil=null 通过 MapStruct 覆盖到 entity，导致已设置的静默期
     * 被普通编辑误清；现在 VO 中已移除该字段、mapper 也 ignore，DB 中 silenceUntil 应保持原值。
     */
    @Test
    void updateShouldNotClearSilenceUntil() throws Exception {
        Date originalSilence = new Date(System.currentTimeMillis() + 30 * 60_000L);
        AlertRule rule = new AlertRule();
        rule.setId(1L);
        rule.setName("旧名称");
        rule.setMetric("cpu");
        rule.setOperator("gt");
        rule.setThreshold(80.0);
        rule.setDurationSec(60);
        rule.setLevel("warning");
        rule.setEnabled(true);
        rule.setSilenceUntil(originalSilence);
        rule.setCreatedAt(new Date());
        rule.setUpdatedAt(new Date());
        ruleStore.put(1L, rule);
        idSeq.set(1);

        // 普通编辑请求：完全不含 silenceUntil 字段
        String payload = """
                {
                  "name": "新名称",
                  "metric": "memory",
                  "operator": "gte",
                  "threshold": 70,
                  "durationSec": 120,
                  "level": "warning",
                  "enabled": false
                }
                """;
        mockMvc.perform(put("/api/alert/rule/1")
                        .contentType("application/json")
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        AlertRule updated = ruleStore.get(1L);
        assertNotNull(updated.getSilenceUntil(), "silenceUntil 不应被普通编辑清空");
        assertEquals(originalSilence.getTime(), updated.getSilenceUntil().getTime(),
                "silenceUntil 应保留原值，实际 " + updated.getSilenceUntil());
        // 其它字段确实更新
        assertEquals("新名称", updated.getName());
        assertEquals("memory", updated.getMetric());
        assertEquals(false, updated.getEnabled());
    }

    /**
     * 回归：即使请求体包含 silenceUntil 字段，反序列化也不应应用到实体（VO 已无该字段）。
     */
    @Test
    void updateShouldIgnoreSilenceUntilEvenIfSentByClient() throws Exception {
        Date originalSilence = new Date(System.currentTimeMillis() + 30 * 60_000L);
        AlertRule rule = new AlertRule();
        rule.setId(1L);
        rule.setName("旧名称");
        rule.setMetric("cpu");
        rule.setOperator("gt");
        rule.setThreshold(80.0);
        rule.setDurationSec(60);
        rule.setLevel("warning");
        rule.setEnabled(true);
        rule.setSilenceUntil(originalSilence);
        rule.setCreatedAt(new Date());
        rule.setUpdatedAt(new Date());
        ruleStore.put(1L, rule);
        idSeq.set(1);

        // 客户端尝试通过 update 请求体清空 silenceUntil
        String payload = """
                {
                  "name": "新名称",
                  "metric": "cpu",
                  "operator": "gt",
                  "threshold": 80,
                  "durationSec": 60,
                  "level": "warning",
                  "enabled": true,
                  "silenceUntil": null
                }
                """;
        mockMvc.perform(put("/api/alert/rule/1")
                        .contentType("application/json")
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        AlertRule updated = ruleStore.get(1L);
        assertNotNull(updated.getSilenceUntil(),
                "VO 不含 silenceUntil 字段，即便请求体显式传 null 也不应清空");
        assertEquals(originalSilence.getTime(), updated.getSilenceUntil().getTime());
    }

    /**
     * 回归：PUT 是完整编辑语义，clientId 显式传 null 应将规则从绑定单客户端改回"全局规则"。
     * <p>
     * 修复点（参见 AlertStructMapper.updateRule 移除 nullValuePropertyMappingStrategy=IGNORE）：
     * 之前为防 silenceUntil 被清空，对整个 BeanMapping 启用 IGNORE，
     * 副作用是 clientId 等所有 null 字段都不覆盖实体，用户在 UI 上把"客户端"下拉清空后
     * 提交也无法把规则改回全局；现改为只针对 silenceUntil / id / createdAt / updatedAt 单独 ignore。
     */
    @Test
    void updateShouldAllowChangingClientIdToNull() throws Exception {
        Date originalSilence = new Date(System.currentTimeMillis() + 30 * 60_000L);
        AlertRule rule = new AlertRule();
        rule.setId(1L);
        rule.setName("绑定主机");
        rule.setClientId(5);
        rule.setMetric("cpu");
        rule.setOperator("gt");
        rule.setThreshold(80.0);
        rule.setDurationSec(60);
        rule.setLevel("warning");
        rule.setEnabled(true);
        rule.setSilenceUntil(originalSilence);
        rule.setCreatedAt(new Date());
        rule.setUpdatedAt(new Date());
        ruleStore.put(1L, rule);
        idSeq.set(1);

        // 用户把"客户端"下拉清空 → 请求体显式传 clientId: null
        String payload = """
                {
                  "name": "改为全局",
                  "clientId": null,
                  "metric": "cpu",
                  "operator": "gt",
                  "threshold": 80,
                  "durationSec": 60,
                  "level": "warning",
                  "enabled": true
                }
                """;
        mockMvc.perform(put("/api/alert/rule/1")
                        .contentType("application/json")
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        AlertRule updated = ruleStore.get(1L);
        assertEquals(null, updated.getClientId(),
                "clientId 应当从 5 被清空为 null（全局规则），但实际仍为 " + updated.getClientId());
        assertEquals("改为全局", updated.getName());
        // 同时静默期不应被影响
        assertNotNull(updated.getSilenceUntil(),
                "silenceUntil 应保留，仅 clientId 被清空");
        assertEquals(originalSilence.getTime(), updated.getSilenceUntil().getTime());
    }

    // ========== 第五轮 P1：AlertRule 字段更新策略注解 ==========

    /**
     * 第五轮 P1：MyBatis-Plus 默认 FieldStrategy.NOT_NULL 会在 null 字段时跳过 SET，
     * 使得用户把 clientId 改 null（绑定主机 → 全局规则）的请求只在内存层生效但 DB 仍保留旧值。
     * <p>
     * 修复要求：AlertRule.clientId 必须标注 @TableField(updateStrategy = FieldStrategy.ALWAYS)，
     * channelIds（用户清空通道也是合法操作）同样标注 ALWAYS；silenceUntil 不应标注 ALWAYS
     * （否则任何 updateById 都会把已设静默期一并清空）。
     */
    @Test
    void updateShouldUseAlwaysStrategyForNullableFields() throws Exception {
        Field clientId = AlertRule.class.getDeclaredField("clientId");
        TableField clientIdAnno = clientId.getAnnotation(TableField.class);
        assertNotNull(clientIdAnno, "AlertRule.clientId 必须标注 @TableField");
        assertEquals(FieldStrategy.ALWAYS, clientIdAnno.updateStrategy(),
                "AlertRule.clientId 必须用 FieldStrategy.ALWAYS，否则 PUT null 改不回全局规则");

        Field channelIds = AlertRule.class.getDeclaredField("channelIds");
        TableField channelIdsAnno = channelIds.getAnnotation(TableField.class);
        assertNotNull(channelIdsAnno, "AlertRule.channelIds 必须标注 @TableField");
        assertEquals(FieldStrategy.ALWAYS, channelIdsAnno.updateStrategy(),
                "AlertRule.channelIds 必须用 FieldStrategy.ALWAYS，否则用户清空通道无法落盘");

        Field silenceUntil = AlertRule.class.getDeclaredField("silenceUntil");
        TableField silenceAnno = silenceUntil.getAnnotation(TableField.class);
        // silenceUntil 应使用默认策略（NOT_NULL），不能标 ALWAYS，否则普通编辑会清空静默期。
        // 默认策略表现为：无 @TableField 注解，或注解但 updateStrategy=DEFAULT/NOT_NULL。
        if (silenceAnno != null) {
            assertFalse(silenceAnno.updateStrategy() == FieldStrategy.ALWAYS,
                    "AlertRule.silenceUntil 不应使用 FieldStrategy.ALWAYS，否则普通编辑会清空静默期");
        }
    }

    // ========== 第五轮 P2：规则变更触发批量 resolve ==========

    /**
     * 第五轮 P2：规则被禁用后，旧 firing 告警在评估器主循环不会再被遍历，
     * 无法走"持续不满足 → 自动 resolve"分支；Controller 必须在 update 端点显式收尾。
     */
    @Test
    void disablingRuleShouldResolveActiveAlerts() throws Exception {
        seedRule(1L, 5, true, "cpu", "gt", 80.0, 60);
        AlertHistory firing = seedHistory(1L, 5, AlertStatus.FIRING.getColumn());
        AlertHistory acked = seedHistory(1L, 5, AlertStatus.ACKNOWLEDGED.getColumn());
        idSeq.set(1);

        String payload = """
                {
                  "name": "r1",
                  "clientId": 5,
                  "metric": "cpu",
                  "operator": "gt",
                  "threshold": 80,
                  "durationSec": 60,
                  "level": "warning",
                  "enabled": false
                }
                """;
        mockMvc.perform(put("/api/alert/rule/1")
                        .contentType("application/json")
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(AlertStatus.RESOLVED.getColumn(), historyStore.get(firing.getId()).getStatus(),
                "firing 告警应在规则禁用时被自动 resolve");
        assertNotNull(historyStore.get(firing.getId()).getResolvedAt(),
                "resolved_at 应被填写");
        assertEquals(AlertStatus.RESOLVED.getColumn(), historyStore.get(acked.getId()).getStatus(),
                "acknowledged 告警同样应在规则禁用时被自动 resolve");
        assertTrue(historyStore.get(firing.getId()).getMessage().contains("规则已禁用"),
                "message 末尾应追加中文原因（实际：" + historyStore.get(firing.getId()).getMessage() + "）");
    }

    /**
     * 第五轮 P2：规则作用域变更（client=5 → client=7）时，仅旧 client=5 的活跃告警应被 resolve；
     * 新作用域的告警由后续评估自然产生。
     */
    @Test
    void changingScopeShouldResolveOldClientAlerts() throws Exception {
        seedRule(1L, 5, true, "cpu", "gt", 80.0, 60);
        AlertHistory oldClientFiring = seedHistory(1L, 5, AlertStatus.FIRING.getColumn());
        AlertHistory newClientFiring = seedHistory(1L, 7, AlertStatus.FIRING.getColumn());
        idSeq.set(1);

        // 改为 clientId=7
        String payload = """
                {
                  "name": "r1",
                  "clientId": 7,
                  "metric": "cpu",
                  "operator": "gt",
                  "threshold": 80,
                  "durationSec": 60,
                  "level": "warning",
                  "enabled": true
                }
                """;
        mockMvc.perform(put("/api/alert/rule/1")
                        .contentType("application/json")
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(AlertStatus.RESOLVED.getColumn(), historyStore.get(oldClientFiring.getId()).getStatus(),
                "旧 clientId=5 的活跃告警应被 resolve");
        assertEquals(AlertStatus.FIRING.getColumn(), historyStore.get(newClientFiring.getId()).getStatus(),
                "新作用域 clientId=7 上的告警不应被本次变更 resolve（由后续评估处理）");
        assertTrue(historyStore.get(oldClientFiring.getId()).getMessage().contains("规则作用域已变更"));
    }

    /**
     * 第五轮 P2：规则阈值变更（80 → 95）时全部活跃告警应 resolve，
     * 让评估器按新阈值重新积累窗口与状态。
     */
    @Test
    void changingThresholdShouldResolveActiveAlerts() throws Exception {
        seedRule(1L, null, true, "cpu", "gt", 80.0, 60);
        AlertHistory firingA = seedHistory(1L, 5, AlertStatus.FIRING.getColumn());
        AlertHistory firingB = seedHistory(1L, 7, AlertStatus.FIRING.getColumn());
        idSeq.set(1);

        // 仅改 threshold；其他不变
        String payload = """
                {
                  "name": "r1",
                  "metric": "cpu",
                  "operator": "gt",
                  "threshold": 95,
                  "durationSec": 60,
                  "level": "warning",
                  "enabled": true
                }
                """;
        mockMvc.perform(put("/api/alert/rule/1")
                        .contentType("application/json")
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(AlertStatus.RESOLVED.getColumn(), historyStore.get(firingA.getId()).getStatus(),
                "阈值变更后该规则所有客户端的活跃告警应被 resolve");
        assertEquals(AlertStatus.RESOLVED.getColumn(), historyStore.get(firingB.getId()).getStatus());
        assertTrue(historyStore.get(firingA.getId()).getMessage().contains("规则条件已变更"));
    }

    /**
     * 第五轮 P2：规则被删除前应先 resolve 全部活跃告警，避免成为"孤儿告警"
     * （history.rule_id 指向已不存在的规则）后永远停在 firing/acknowledged 状态。
     */
    @Test
    void deletingRuleShouldResolveActiveAlerts() throws Exception {
        seedRule(1L, 5, true, "cpu", "gt", 80.0, 60);
        AlertHistory firing = seedHistory(1L, 5, AlertStatus.FIRING.getColumn());
        AlertHistory acked = seedHistory(1L, 5, AlertStatus.ACKNOWLEDGED.getColumn());

        mockMvc.perform(delete("/api/alert/rule/1")
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertNull(ruleStore.get(1L), "规则应被删除");
        assertEquals(AlertStatus.RESOLVED.getColumn(), historyStore.get(firing.getId()).getStatus(),
                "firing 告警应在规则删除前被 resolve");
        assertEquals(AlertStatus.RESOLVED.getColumn(), historyStore.get(acked.getId()).getStatus(),
                "acknowledged 告警同样应在规则删除前被 resolve");
        assertTrue(historyStore.get(firing.getId()).getMessage().contains("规则已删除"));
    }

    /**
     * 第五轮 P2：普通编辑（仅改 name / level / 通道）不应触发批量 resolve。
     */
    @Test
    void cosmeticEditShouldNotResolveActiveAlerts() throws Exception {
        seedRule(1L, 5, true, "cpu", "gt", 80.0, 60);
        AlertHistory firing = seedHistory(1L, 5, AlertStatus.FIRING.getColumn());

        // 仅改 name 与 level；不动 enabled / clientId / metric / operator / threshold / durationSec
        String payload = """
                {
                  "name": "新名字",
                  "clientId": 5,
                  "metric": "cpu",
                  "operator": "gt",
                  "threshold": 80,
                  "durationSec": 60,
                  "level": "critical",
                  "enabled": true
                }
                """;
        mockMvc.perform(put("/api/alert/rule/1")
                        .contentType("application/json")
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin")
                        .content(payload))
                .andExpect(status().isOk());

        assertEquals(AlertStatus.FIRING.getColumn(), historyStore.get(firing.getId()).getStatus(),
                "name/level 等非关键字段编辑不应触发批量 resolve");
    }

    /**
     * 第五轮 P2：AlertWindowCache.clearByRule(ruleId) 应删除该规则下所有
     * (ruleId, clientId) 窗口与锁，避免变更后旧样本污染新评估。
     */
    @Test
    void alertWindowCacheClearByRuleShouldRemoveAllClientWindows() {
        AlertWindowCache cache = new AlertWindowCache();
        cache.record(42L, 1, true, 60);
        cache.record(42L, 2, true, 60);
        cache.record(43L, 1, true, 60);
        // 通过 lockFor 触发锁映射创建
        Object lock1 = cache.lockFor(42L, 1);
        Object lock2 = cache.lockFor(42L, 2);
        Object lockOther = cache.lockFor(43L, 1);
        assertNotNull(lock1);
        assertNotNull(lock2);
        assertNotNull(lockOther);

        cache.clearByRule(42L);

        // 同一 (ruleId=42) 的窗口与锁应被清空；其他规则不受影响
        assertFalse(cache.isContinuouslyMet(42L, 1, 1),
                "ruleId=42, clientId=1 的窗口应被清空");
        assertFalse(cache.isContinuouslyMet(42L, 2, 1),
                "ruleId=42, clientId=2 的窗口应被清空");
        // ruleId=43 应保留
        cache.record(43L, 1, true, 1);
        // 锁对象在清空后再 lockFor 应是新对象（而非 stale）
        Object lock1AfterClear = cache.lockFor(42L, 1);
        assertTrue(lock1 != lock1AfterClear,
                "clearByRule 后再 lockFor 应得到新锁对象");
    }
}
