package com.example.service;

import com.example.entity.dto.Client;
import com.example.entity.dto.StatusPageConfig;
import com.example.entity.vo.request.StatusPageConfigUpdateVO;
import com.example.entity.vo.response.StatusPageClientVO;
import com.example.entity.vo.response.StatusPageConfigVO;
import com.example.entity.vo.response.StatusPageSummaryVO;
import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.entity.vo.response.RuntimeHistoryVO;
import com.example.service.impl.StatusPageServiceImpl;
import com.example.tsdb.TimeSeriesAdapter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link StatusPageServiceImpl} 单元测试。
 *
 * <p>沿用项目惯例：JDK 动态代理替代 Mockito。覆盖：
 * <ul>
 *   <li>未启用时返回空客户端列表但保留标题；</li>
 *   <li>显式空 client_ids → 公开零个客户端；</li>
 *   <li>{@code clientIds == null} → 默认公开所有；</li>
 *   <li>30s 缓存：第二次调用 {@code getCachedSummary} 不会再触发 InfluxDB 查询（AC10）；</li>
 *   <li>{@code updateConfig} 失效缓存，下一次访问重新计算；</li>
 *   <li>InfluxDB 抛异常时单 client 走 availability = null 兜底；</li>
 *   <li>{@code displayName} 留空时回退到内部 {@code name}；</li>
 *   <li>状态页响应严格白名单字段（不包含 IP/CPU/内存/磁盘/OS 等）；</li>
 *   <li>{@code clientIds} CSV 序列化/反序列化正确；</li>
 *   <li>{@code overallAvailability} 等于参与客户端可用率平均值。</li>
 * </ul>
 */
class StatusPageServiceImplTest {

    private StatusPageServiceImpl service;
    /**
     * 内存版状态页配置存储（id=1 单行）。
     */
    private final Map<Integer, StatusPageConfig> configRows = new HashMap<>();
    /**
     * 内存版客户端列表，供 ClientService.list / findClientById 走查。
     */
    private final List<Client> clientRows = new ArrayList<>();
    /**
     * 跟踪 InfluxDB 查询次数（验证缓存命中）。
     */
    private final AtomicInteger influxQueryCount = new AtomicInteger(0);
    /**
     * 跟踪 isClientOnline 调用计数；用来对单 client 进行覆盖式 stub。
     */
    private final Map<Integer, Boolean> clientOnlineMap = new HashMap<>();
    /**
     * 每个客户端的可用率桶序列；空数组表示无数据；null 表示 stub 抛异常。
     */
    private final Map<Integer, double[]> availabilityBuckets = new HashMap<>();

    @BeforeEach
    void setUp() {
        configRows.clear();
        clientRows.clear();
        influxQueryCount.set(0);
        clientOnlineMap.clear();
        availabilityBuckets.clear();
        seedDefaultConfig();
        service = newServiceInstance();
    }

    private StatusPageServiceImpl newServiceInstance() {
        StatusPageServiceImpl impl = new StatusPageServiceImpl() {
            @Override
            public StatusPageConfig getById(java.io.Serializable id) {
                return configRows.get(((Number) id).intValue());
            }

            @Override
            public boolean updateById(StatusPageConfig entity) {
                configRows.put(entity.getId(), entity);
                return true;
            }
        };
        ReflectionTestUtils.setField(impl, "cacheTtlSeconds", 30L);

        ClientService clientService = (ClientService) Proxy.newProxyInstance(
                ClientService.class.getClassLoader(),
                new Class[]{ClientService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "list" -> new ArrayList<>(clientRows);
                    case "findClientById" -> {
                        int id = ((Number) args[0]).intValue();
                        yield clientRows.stream().filter(c -> c.getId() == id).findFirst().orElse(null);
                    }
                    case "isClientOnline" -> {
                        int id = ((Number) args[0]).intValue();
                        yield clientOnlineMap.getOrDefault(id, Boolean.FALSE);
                    }
                    case "lastSeenSecondsAgo" -> 12L;
                    default -> null;
                });
        ReflectionTestUtils.setField(impl, "clientService", clientService);

        TimeSeriesAdapter influxDbUtils = new TimeSeriesAdapter() {
            @Override
            public void writeRuntime(int clientId, RuntimeDetailVO vo) {
                // status-page tests only exercise read path
            }

            @Override
            public void writeOtlpMetric(int clientId, RuntimeDetailVO vo) {
                // status-page tests only exercise read path
            }

            @Override
            public RuntimeHistoryVO readRuntimeHistory(int clientId) {
                return new RuntimeHistoryVO();
            }

            @Override
            public double[] readAvailabilityBuckets(int clientId) {
                influxQueryCount.incrementAndGet();
                double[] buckets = availabilityBuckets.get(clientId);
                if (buckets == null) {
                    throw new RuntimeException("simulated influx failure");
                }
                return buckets;
            }
        };
        ReflectionTestUtils.setField(impl, "influxDbUtils", influxDbUtils);
        return impl;
    }

    private void seedDefaultConfig() {
        StatusPageConfig cfg = new StatusPageConfig();
        cfg.setId(1);
        cfg.setTitle("Service Status");
        cfg.setEnabled(Boolean.TRUE);
        cfg.setClientIds(null);
        configRows.put(1, cfg);
    }

    private Client newClient(int id, String name, String displayName) {
        Client c = new Client();
        c.setId(id);
        c.setName(name);
        c.setDisplayName(displayName);
        c.setLocation("cn");
        c.setNode("default");
        c.setRegisterTime(new Date());
        c.setToken("token-" + id);
        return c;
    }

    private double[] bucketsAllOne(int n) {
        double[] arr = new double[n];
        java.util.Arrays.fill(arr, 1.0);
        return arr;
    }

    /**
     * 禁用时返回标题但客户端为空。
     */
    @Test
    void disabledConfigReturnsEmptyClientList() {
        StatusPageConfig cfg = configRows.get(1);
        cfg.setEnabled(Boolean.FALSE);
        cfg.setTitle("Hello");
        clientRows.add(newClient(1, "internal-host", "Web Server"));
        availabilityBuckets.put(1, bucketsAllOne(48));

        StatusPageSummaryVO vo = service.getCachedSummary();
        Assertions.assertEquals("Hello", vo.getTitle());
        Assertions.assertTrue(vo.getClients().isEmpty());
        Assertions.assertNull(vo.getOverallAvailability());
        // 禁用时不应触发 InfluxDB 查询
        Assertions.assertEquals(0, influxQueryCount.get());
    }

    /**
     * 空 client_ids 字符串表示明确公开零个客户端。
     */
    @Test
    void emptyClientIdsCsvHidesAllClients() {
        StatusPageConfig cfg = configRows.get(1);
        cfg.setClientIds("");
        clientRows.add(newClient(1, "internal-host", null));
        availabilityBuckets.put(1, bucketsAllOne(48));

        StatusPageSummaryVO vo = service.getCachedSummary();
        Assertions.assertTrue(vo.getClients().isEmpty());
    }

    /**
     * P2-3：默认 default-deny。{@code clientIds == null} 不再表示"公开所有客户端"。
     * 必须由管理员显式选择 client_ids 才能公开。
     */
    @Test
    void nullClientIdsHidesAllClientsByDefault() {
        clientRows.add(newClient(1, "host-1", "Web Server"));
        clientRows.add(newClient(2, "host-2", null));
        availabilityBuckets.put(1, bucketsAllOne(48));
        availabilityBuckets.put(2, bucketsAllOne(48));
        clientOnlineMap.put(1, true);
        clientOnlineMap.put(2, false);

        StatusPageSummaryVO vo = service.getCachedSummary();
        Assertions.assertTrue(vo.getClients().isEmpty(),
                "P2-3 default-deny：clientIds=null 必须返回空，管理员未主动选择则不公开任何客户端");
        Assertions.assertNull(vo.getOverallAvailability());
    }

    /**
     * P2-3：默认种子（enabled=0 + client_ids=''）等价于 default-deny。
     */
    @Test
    void postMigrationDefaultIsDeny() {
        StatusPageConfig cfg = configRows.get(1);
        cfg.setEnabled(Boolean.FALSE);
        cfg.setClientIds("");
        clientRows.add(newClient(1, "host", "Host"));
        availabilityBuckets.put(1, bucketsAllOne(48));

        StatusPageSummaryVO vo = service.getCachedSummary();
        Assertions.assertTrue(vo.getClients().isEmpty(),
                "P2-3：迁移后默认 enabled=false + client_ids='' 必须不暴露任何客户端");
        Assertions.assertEquals(0, influxQueryCount.get(),
                "P2-3：禁用时不应触发 InfluxDB 查询");
    }

    /**
     * 即使 enabled=true 但未勾选任何客户端，依然不返回客户端列表。
     */
    @Test
    void enabledWithoutAnyClientSelectionStillHidesClients() {
        StatusPageConfig cfg = configRows.get(1);
        cfg.setEnabled(Boolean.TRUE);
        cfg.setClientIds(""); // 显式空：管理员开启但未选客户端
        clientRows.add(newClient(1, "host-1", "Web Server"));
        availabilityBuckets.put(1, bucketsAllOne(48));

        StatusPageSummaryVO vo = service.getCachedSummary();
        Assertions.assertTrue(vo.getClients().isEmpty());
    }

    /**
     * AC10：30s 缓存窗口内多次调用，InfluxDB 只查询 1 次（每客户端 1 次）。
     */
    @Test
    void cacheShouldOnlyQueryInfluxOncePerWindow() {
        clientRows.add(newClient(1, "host-1", "Web"));
        availabilityBuckets.put(1, bucketsAllOne(48));
        // P2-3：default-deny 后必须显式选定客户端
        configRows.get(1).setClientIds("1");

        for (int i = 0; i < 100; i++) {
            service.getCachedSummary();
        }
        Assertions.assertEquals(1, influxQueryCount.get(),
                "AC10：30s 内 100 次访问，单客户端只查 Influx 一次");
    }

    /**
     * updateConfig 应失效缓存，下一次 getCachedSummary 重新查询 InfluxDB。
     */
    @Test
    void updateConfigShouldInvalidateCache() {
        clientRows.add(newClient(1, "host-1", "Web"));
        availabilityBuckets.put(1, bucketsAllOne(48));
        configRows.get(1).setClientIds("1");

        service.getCachedSummary();
        Assertions.assertEquals(1, influxQueryCount.get());

        StatusPageConfigUpdateVO upd = new StatusPageConfigUpdateVO();
        upd.setTitle("New Title");
        upd.setEnabled(Boolean.TRUE);
        upd.setClientIds(List.of(1));
        service.updateConfig(upd);

        service.getCachedSummary();
        Assertions.assertEquals(2, influxQueryCount.get(), "更新配置后缓存应失效，重新查询");
    }

    /**
     * InfluxDB 单 client 抛异常 → 该 client 走 availability = null 兜底，整体不 500。
     */
    @Test
    void influxFailureFallsBackPerClient() {
        clientRows.add(newClient(1, "ok-host", "OK Host"));
        clientRows.add(newClient(2, "bad-host", "Bad Host"));
        availabilityBuckets.put(1, bucketsAllOne(48));
        // 2 号 client 故意不放入，触发异常分支
        clientOnlineMap.put(1, true);
        clientOnlineMap.put(2, false);
        configRows.get(1).setClientIds("1,2");

        StatusPageSummaryVO vo = service.getCachedSummary();
        Assertions.assertEquals(2, vo.getClients().size());
        StatusPageClientVO okClient = vo.getClients().stream()
                .filter(c -> "OK Host".equals(c.getDisplayName())).findFirst().orElseThrow();
        Assertions.assertEquals(1.0, okClient.getAvailability24h());
        StatusPageClientVO badClient = vo.getClients().stream()
                .filter(c -> "Bad Host".equals(c.getDisplayName())).findFirst().orElseThrow();
        Assertions.assertNull(badClient.getAvailability24h(),
                "InfluxDB 异常 → availability24h 必须为 null");
        Assertions.assertTrue(badClient.getBuckets().isEmpty());
        // 整体可用率 = 仅算成功的 1.0 → 1.0
        Assertions.assertEquals(1.0, vo.getOverallAvailability());
    }

    /**
     * displayName 留空时回退到内部 name。
     */
    @Test
    void displayNameFallsBackToInternalName() {
        clientRows.add(newClient(1, "prod-db-01", null));
        availabilityBuckets.put(1, bucketsAllOne(48));
        configRows.get(1).setClientIds("1");

        StatusPageSummaryVO vo = service.getCachedSummary();
        Assertions.assertEquals(1, vo.getClients().size());
        Assertions.assertEquals("prod-db-01", vo.getClients().get(0).getDisplayName(),
                "未配置 displayName 时回退到 name");
    }

    /**
     * 验证响应严格白名单：JSON 序列化后不含敏感字段名（IP/CPU/内存/磁盘/OS/...）。
     */
    @Test
    void summaryResponseShouldExcludeSensitiveFields() {
        clientRows.add(newClient(1, "internal-host", "Web Server"));
        availabilityBuckets.put(1, bucketsAllOne(48));
        clientOnlineMap.put(1, true);
        configRows.get(1).setClientIds("1");

        StatusPageSummaryVO vo = service.getCachedSummary();
        String json = com.alibaba.fastjson2.JSON.toJSONString(vo);
        // 严格白名单：不应出现的字段名
        String[] forbidden = {
                "\"ip\"", "\"cpuUsage\"", "\"memoryUsage\"", "\"diskUsage\"",
                "\"networkUpload\"", "\"networkDownload\"",
                "\"diskRead\"", "\"diskWrite\"",
                "\"osName\"", "\"osVersion\"",
                "\"cpuName\"", "\"cpuCore\"", "\"memory\"",
                "\"token\"", "\"registerTime\"", "\"node\""
        };
        for (String banned : forbidden) {
            Assertions.assertFalse(json.contains(banned),
                    "响应 JSON 不应出现敏感字段 " + banned + "，但实际包含。json=" + json);
        }
        // 同时验证 internal name 也未被泄露（displayName 已设为 "Web Server"，故 internal-host 不应出现）
        Assertions.assertFalse(json.contains("internal-host"),
                "内部 client.name 不应出现在公开响应中，但实际包含。json=" + json);
    }

    /**
     * client_ids 含部分受配置覆盖的客户端 → 仅返回选中的 client。
     */
    @Test
    void clientIdsCsvFiltersClients() {
        StatusPageConfig cfg = configRows.get(1);
        cfg.setClientIds("1,3");
        clientRows.add(newClient(1, "h1", "Host 1"));
        clientRows.add(newClient(2, "h2", "Host 2"));
        clientRows.add(newClient(3, "h3", "Host 3"));
        availabilityBuckets.put(1, bucketsAllOne(48));
        availabilityBuckets.put(3, bucketsAllOne(48));

        StatusPageSummaryVO vo = service.getCachedSummary();
        Assertions.assertEquals(2, vo.getClients().size());
        Assertions.assertEquals("Host 1", vo.getClients().get(0).getDisplayName());
        Assertions.assertEquals("Host 3", vo.getClients().get(1).getDisplayName());
    }

    /**
     * getAdminConfig 返回完整配置以及候选客户端列表。
     */
    @Test
    void adminConfigShouldExposeCandidateClients() {
        clientRows.add(newClient(1, "h1", "Host 1"));
        clientRows.add(newClient(2, "h2", null));
        StatusPageConfig cfg = configRows.get(1);
        cfg.setClientIds("1,2");
        cfg.setTitle("Status");

        StatusPageConfigVO admin = service.getAdminConfig();
        Assertions.assertEquals("Status", admin.getTitle());
        Assertions.assertEquals(List.of(1, 2), admin.getClientIds());
        Assertions.assertEquals(2, admin.getAvailableClients().size());
        Assertions.assertEquals("h1", admin.getAvailableClients().get(0).getName());
        Assertions.assertEquals("Host 1", admin.getAvailableClients().get(0).getDisplayName());
    }

    /**
     * updateConfig 使用 null clientIds → 配置写为 null（默认公开所有），
     * 反序列化时返回 null List。
     */
    @Test
    void updateConfigWithNullClientIdsSerializesAsNull() {
        StatusPageConfigUpdateVO upd = new StatusPageConfigUpdateVO();
        upd.setTitle("X");
        upd.setEnabled(Boolean.TRUE);
        upd.setClientIds(null);
        StatusPageConfigVO result = service.updateConfig(upd);
        Assertions.assertNull(result.getClientIds());
        StatusPageConfig stored = configRows.get(1);
        Assertions.assertNull(stored.getClientIds());
    }

    /**
     * updateConfig 使用空列表 → 配置写为空字符串（"明确清空"语义）。
     */
    @Test
    void updateConfigWithEmptyClientIdsSerializesAsEmptyString() {
        StatusPageConfigUpdateVO upd = new StatusPageConfigUpdateVO();
        upd.setTitle("X");
        upd.setEnabled(Boolean.TRUE);
        upd.setClientIds(List.of());
        StatusPageConfigVO result = service.updateConfig(upd);
        Assertions.assertEquals(List.of(), result.getClientIds());
        StatusPageConfig stored = configRows.get(1);
        Assertions.assertEquals("", stored.getClientIds());
    }
}
