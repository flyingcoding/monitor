package com.example.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.entity.dto.Account;
import com.example.entity.dto.AlertHistory;
import com.example.entity.dto.AlertRule;
import com.example.mapper.struct.AlertStructMapper;
import com.example.mapper.struct.AlertStructMapperImpl;
import com.example.service.AccountService;
import com.example.service.AlertHistoryService;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AlertHistoryControllerTest {

    private MockMvc mockMvc;
    private final Map<Long, AlertHistory> historyStore = new HashMap<>();
    private final Map<Long, AlertRule> ruleStore = new HashMap<>();
    private final AtomicReference<Account> currentAccount = new AtomicReference<>();
    private final AtomicReference<Collection<Integer>> lastFilterClientIds = new AtomicReference<>();
    private final AtomicReference<Integer> lastFilterSingleClientId = new AtomicReference<>();

    /**
     * 构建测试上下文：将所有依赖通过轻量 Proxy 桩化，注入真实 PermissionService 用于权限判定，
     * 并通过 currentAccount 控制每次测试的账号身份。
     */
    @BeforeEach
    void setUp() {
        historyStore.clear();
        ruleStore.clear();
        lastFilterClientIds.set(null);
        lastFilterSingleClientId.set(null);

        AlertHistoryController controller = new AlertHistoryController();

        AlertHistoryService alertHistoryService = (AlertHistoryService) Proxy.newProxyInstance(
                AlertHistoryService.class.getClassLoader(),
                new Class[]{AlertHistoryService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getById" -> historyStore.get(((Number) args[0]).longValue());
                    case "updateById" -> {
                        AlertHistory h = (AlertHistory) args[0];
                        historyStore.put(h.getId(), h);
                        yield true;
                    }
                    case "queryHistory" -> {
                        @SuppressWarnings("unchecked")
                        Collection<Integer> ids = (Collection<Integer>) args[0];
                        Integer single = (Integer) args[1];
                        String level = (String) args[2];
                        String status = (String) args[3];
                        Date from = (Date) args[4];
                        Date to = (Date) args[5];
                        int page = (int) args[6];
                        int size = (int) args[7];

                        lastFilterClientIds.set(ids);
                        lastFilterSingleClientId.set(single);

                        // 非管理员且 ids 为空集合时直接空页
                        if (ids != null && ids.isEmpty()) {
                            Page<AlertHistory> emptyPage = new Page<>(page, size);
                            emptyPage.setRecords(List.of());
                            emptyPage.setTotal(0);
                            yield emptyPage;
                        }

                        List<AlertHistory> filtered = new ArrayList<>();
                        for (AlertHistory h : historyStore.values()) {
                            if (ids != null && !ids.contains(h.getClientId())) continue;
                            if (single != null && !single.equals(h.getClientId())) continue;
                            if (level != null && !level.isBlank() && !level.equals(h.getLevel())) continue;
                            if (status != null && !status.isBlank() && !status.equals(h.getStatus())) continue;
                            if (from != null && h.getFiredAt() != null && h.getFiredAt().before(from)) continue;
                            if (to != null && h.getFiredAt() != null && h.getFiredAt().after(to)) continue;
                            filtered.add(h);
                        }
                        // 按 fired_at 倒序
                        filtered.sort((a, b) -> {
                            if (a.getFiredAt() == null) return 1;
                            if (b.getFiredAt() == null) return -1;
                            return b.getFiredAt().compareTo(a.getFiredAt());
                        });
                        Page<AlertHistory> pageResult = new Page<>(page, size);
                        pageResult.setRecords(filtered);
                        pageResult.setTotal(filtered.size());
                        yield pageResult;
                    }
                    case "toString" -> "AlertHistoryServiceStub";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException("测试桩未实现方法: " + method.getName());
                });

        AlertRuleService alertRuleService = (AlertRuleService) Proxy.newProxyInstance(
                AlertRuleService.class.getClassLoader(),
                new Class[]{AlertRuleService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getById" -> ruleStore.get(((Number) args[0]).longValue());
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

        AccountService accountService = (AccountService) Proxy.newProxyInstance(
                AccountService.class.getClassLoader(),
                new Class[]{AccountService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getById" -> currentAccount.get();
                    case "toString" -> "AccountServiceStub";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException("测试桩未实现方法: " + method.getName());
                });

        PermissionService permissionService = new PermissionService();
        ReflectionTestUtils.setField(permissionService, "accountService", accountService);
        AlertStructMapper alertStructMapper = new AlertStructMapperImpl();

        ReflectionTestUtils.setField(controller, "alertHistoryService", alertHistoryService);
        ReflectionTestUtils.setField(controller, "alertRuleService", alertRuleService);
        ReflectionTestUtils.setField(controller, "permissionService", permissionService);
        ReflectionTestUtils.setField(controller, "alertStructMapper", alertStructMapper);

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /**
     * 管理员可查看全部告警历史（queryHistory 传入的 ids 应为 null）。
     */
    @Test
    void adminCanListAllHistory() throws Exception {
        seedHistory(1L, 1001, "warning", "firing", new Date());
        seedHistory(2L, 1002, "critical", "firing", new Date());

        mockMvc.perform(get("/api/alert/history")
                        .requestAttr(Const.ATTR_USER_ID, 100)
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records.length()").value(2));

        assertEquals(null, lastFilterClientIds.get(), "管理员调用应传入 null 表示不限制 client_id");
    }

    /**
     * 子账户只能看到自己可访问的客户端范围内的告警历史。
     */
    @Test
    void subAccountListShouldBeFilteredByAllowedClients() throws Exception {
        // 子账户仅可访问 client 1001
        currentAccount.set(new Account(7, "u1", "p", "u1@test.com", "user", "[1001]", null));
        seedHistory(1L, 1001, "warning", "firing", new Date());
        seedHistory(2L, 1002, "critical", "firing", new Date());

        mockMvc.perform(get("/api/alert/history")
                        .requestAttr(Const.ATTR_USER_ID, 7)
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].clientId").value(1001));

        Collection<Integer> ids = lastFilterClientIds.get();
        assertNotNull(ids);
        assertEquals(1, ids.size());
        assertEquals(true, ids.contains(1001));
    }

    /**
     * 子账户传入越权 clientId 应返回 403。
     */
    @Test
    void subAccountListShouldRejectOutOfScopeClientFilter() throws Exception {
        currentAccount.set(new Account(7, "u1", "p", "u1@test.com", "user", "[1001]", null));

        mockMvc.perform(get("/api/alert/history")
                        .param("clientId", "1002")
                        .requestAttr(Const.ATTR_USER_ID, 7)
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_user"))
                .andExpect(status().isForbidden());
    }

    /**
     * 详情接口对越权 history 返回 403。
     */
    @Test
    void detailShouldRejectOutOfScope() throws Exception {
        currentAccount.set(new Account(7, "u1", "p", "u1@test.com", "user", "[1001]", null));
        seedHistory(1L, 1002, "critical", "firing", new Date());

        mockMvc.perform(get("/api/alert/history/1")
                        .requestAttr(Const.ATTR_USER_ID, 7)
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_user"))
                .andExpect(status().isForbidden());
    }

    /**
     * 详情接口对不存在的 id 返回 404。
     */
    @Test
    void detailShouldReturnNotFoundWhenMissing() throws Exception {
        mockMvc.perform(get("/api/alert/history/999")
                        .requestAttr(Const.ATTR_USER_ID, 100)
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin"))
                .andExpect(status().isNotFound());
    }

    /**
     * 详情接口在管理员视角下返回 ruleName 填充字段。
     */
    @Test
    void detailShouldPopulateRuleName() throws Exception {
        AlertRule rule = new AlertRule();
        rule.setId(10L);
        rule.setName("CPU过高");
        rule.setMetric("cpu");
        rule.setOperator("gt");
        rule.setThreshold(80.0);
        rule.setDurationSec(60);
        rule.setLevel("warning");
        rule.setEnabled(true);
        rule.setCreatedAt(new Date());
        rule.setUpdatedAt(new Date());
        ruleStore.put(10L, rule);

        AlertHistory h = seedHistory(1L, 1001, "warning", "firing", new Date());
        h.setRuleId(10L);

        mockMvc.perform(get("/api/alert/history/1")
                        .requestAttr(Const.ATTR_USER_ID, 100)
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.ruleName").value("CPU过高"));
    }

    /**
     * 列表接口应当从关联的 alert_rule 中读取 metric 并填充到 VO，
     * 用于前端按指标决定数值单位（% / KB/s）。
     */
    @Test
    void listShouldIncludeMetricFromRule() throws Exception {
        AlertRule rule = new AlertRule();
        rule.setId(20L);
        rule.setName("CPU过高");
        rule.setMetric("cpu");
        rule.setOperator("gt");
        rule.setThreshold(80.0);
        rule.setDurationSec(60);
        rule.setLevel("warning");
        rule.setEnabled(true);
        rule.setCreatedAt(new Date());
        rule.setUpdatedAt(new Date());
        ruleStore.put(20L, rule);

        AlertHistory h = seedHistory(1L, 1001, "warning", "firing", new Date());
        h.setRuleId(20L);

        mockMvc.perform(get("/api/alert/history")
                        .requestAttr(Const.ATTR_USER_ID, 100)
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].metric").value("cpu"))
                .andExpect(jsonPath("$.data.records[0].ruleName").value("CPU过高"));
    }

    /**
     * 详情接口同样需要返回 metric，便于详情抽屉中按指标渲染单位。
     */
    @Test
    void detailShouldIncludeMetric() throws Exception {
        AlertRule rule = new AlertRule();
        rule.setId(30L);
        rule.setName("内存过高");
        rule.setMetric("memory");
        rule.setOperator("gt");
        rule.setThreshold(90.0);
        rule.setDurationSec(60);
        rule.setLevel("critical");
        rule.setEnabled(true);
        rule.setCreatedAt(new Date());
        rule.setUpdatedAt(new Date());
        ruleStore.put(30L, rule);

        AlertHistory h = seedHistory(1L, 1001, "critical", "firing", new Date());
        h.setRuleId(30L);

        mockMvc.perform(get("/api/alert/history/1")
                        .requestAttr(Const.ATTR_USER_ID, 100)
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.metric").value("memory"))
                .andExpect(jsonPath("$.data.ruleName").value("内存过高"));
    }

    /**
     * ack 操作应更新状态、ackedBy、ackedAt。
     */
    @Test
    void ackShouldUpdateStatusAndAuditFields() throws Exception {
        AlertHistory h = seedHistory(1L, 1001, "warning", "firing", new Date());

        mockMvc.perform(post("/api/alert/history/1/ack")
                        .requestAttr(Const.ATTR_USER_ID, 42)
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        AlertHistory updated = historyStore.get(1L);
        assertEquals("acknowledged", updated.getStatus());
        assertEquals(42, updated.getAckedBy());
        assertNotNull(updated.getAckedAt());
    }

    /**
     * ack 已 resolved 的告警应返回 409。
     */
    @Test
    void ackShouldRejectAlreadyResolved() throws Exception {
        AlertHistory h = seedHistory(1L, 1001, "warning", "resolved", new Date());
        h.setResolvedAt(new Date());

        mockMvc.perform(post("/api/alert/history/1/ack")
                        .requestAttr(Const.ATTR_USER_ID, 42)
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin"))
                .andExpect(status().isConflict());
    }

    /**
     * close 操作应更新状态为 resolved 并写入 resolvedAt。
     */
    @Test
    void closeShouldMarkResolved() throws Exception {
        seedHistory(1L, 1001, "warning", "firing", new Date());

        mockMvc.perform(post("/api/alert/history/1/close")
                        .requestAttr(Const.ATTR_USER_ID, 42)
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        AlertHistory updated = historyStore.get(1L);
        assertEquals("resolved", updated.getStatus());
        assertNotNull(updated.getResolvedAt());
    }

    /**
     * 子账户对自己范围外告警进行 ack 应返回 403。
     */
    @Test
    void ackShouldRejectOutOfScope() throws Exception {
        currentAccount.set(new Account(7, "u1", "p", "u1@test.com", "user", "[1001]", null));
        seedHistory(1L, 1002, "warning", "firing", new Date());

        mockMvc.perform(post("/api/alert/history/1/ack")
                        .requestAttr(Const.ATTR_USER_ID, 7)
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_user"))
                .andExpect(status().isForbidden());

        AlertHistory unchanged = historyStore.get(1L);
        assertEquals("firing", unchanged.getStatus());
    }

    /**
     * 子账户对自己范围内告警可成功 close。
     */
    @Test
    void subAccountCanCloseHistoryInScope() throws Exception {
        currentAccount.set(new Account(7, "u1", "p", "u1@test.com", "user", "[1001]", null));
        seedHistory(1L, 1001, "warning", "firing", new Date());

        mockMvc.perform(post("/api/alert/history/1/close")
                        .requestAttr(Const.ATTR_USER_ID, 7)
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        AlertHistory updated = historyStore.get(1L);
        assertEquals("resolved", updated.getStatus());
    }

    /**
     * 分页参数越界（size>100）应返回 400。
     */
    @Test
    void listShouldRejectInvalidPageSize() throws Exception {
        mockMvc.perform(get("/api/alert/history")
                        .param("size", "200")
                        .requestAttr(Const.ATTR_USER_ID, 100)
                        .requestAttr(Const.ATTR_USER_ROLE, "ROLE_admin"))
                .andExpect(status().isBadRequest());
    }

    /**
     * 在 historyStore 中插入测试数据并返回插入实体。
     *
     * @param id       告警历史ID
     * @param clientId 客户端ID
     * @param level    等级
     * @param status   状态
     * @param firedAt  触发时间
     * @return 插入的告警实体
     */
    private AlertHistory seedHistory(long id, int clientId, String level, String status, Date firedAt) {
        AlertHistory h = new AlertHistory();
        h.setId(id);
        h.setClientId(clientId);
        h.setLevel(level);
        h.setStatus(status);
        h.setFiredAt(firedAt);
        historyStore.put(id, h);
        return h;
    }
}
