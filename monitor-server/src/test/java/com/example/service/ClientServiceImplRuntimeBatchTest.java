package com.example.service;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.example.entity.dto.Account;
import com.example.entity.dto.Client;
import com.example.entity.dto.StatusPageConfig;
import com.example.config.SseEventBus;
import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.entity.vo.response.AlertHistoryVO;
import com.example.entity.vo.response.GpuSnapshotResponseVO;
import com.example.entity.vo.response.ProcessSnapshotResponseVO;
import com.example.entity.vo.response.RuntimeHistoryVO;
import com.example.entity.vo.response.SmartSnapshotResponseVO;
import com.example.entity.vo.response.SystemdSnapshotResponseVO;
import com.example.mapper.AccountMapper;
import com.example.mapper.AlertHistoryMapper;
import com.example.mapper.AlertRuleMapper;
import com.example.mapper.ClientDetailMapper;
import com.example.mapper.ClientMapper;
import com.example.mapper.ClientSshMapper;
import com.example.mapper.StatusPageConfigMapper;
import com.example.service.impl.ClientServiceImpl;
import com.example.tsdb.TimeSeriesAdapter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.Serializable;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests runtime batch reporting paths in {@link ClientServiceImpl}.
 */
class ClientServiceImplRuntimeBatchTest {

    @Test
    void updateRuntimeDetailsShouldBatchTsdbAndKeepPerRuntimeSideEffects() {
        ClientServiceImpl service = new ClientServiceImpl();
        RecordingTimeSeriesAdapter adapter = new RecordingTimeSeriesAdapter();
        RecordingSseEventBus sseEventBus = new RecordingSseEventBus();
        AtomicInteger alertCount = new AtomicInteger(0);
        ReflectionTestUtils.setField(service, "influx", adapter);
        ReflectionTestUtils.setField(service, "sseEventBus", sseEventBus);
        ReflectionTestUtils.setField(service, "alertEvaluator",
                (AlertEvaluator) (clientId, runtime) -> alertCount.incrementAndGet());

        Client client = new Client(42, "host", "token", "cn", "node", new Date(), null);
        List<RuntimeDetailVO> batch = List.of(runtime(1_700_000_000_000L), runtime(1_700_000_010_000L));

        service.updateRuntimeDetails(batch, client);

        Assertions.assertEquals(1, adapter.batchWriteCount, "TSDB 应只执行一次批量写入");
        Assertions.assertEquals(0, adapter.singleWriteCount, "批量路径不应退回逐条 TSDB 写入");
        Assertions.assertEquals(2, adapter.lastBatchSize);
        Assertions.assertEquals(2, sseEventBus.runtimeCount.get(), "runtime SSE 仍保持逐条推送");
        Assertions.assertEquals(2, sseEventBus.clientListCount.get(), "client-list SSE 仍保持逐条刷新");
        Assertions.assertEquals(2, alertCount.get(), "告警评估仍保持逐条执行");
    }

    @Test
    void deleteClientShouldRemoveMysqlReferencesAndKeepTsdbHistory() {
        Client existing = new Client(42, "host", "token-42", "cn", "node", new Date(), null);
        TestableClientServiceImpl service = new TestableClientServiceImpl(existing);
        RecordingClientDetailMapper detailMapper = new RecordingClientDetailMapper();
        RecordingClientSshMapper sshMapper = new RecordingClientSshMapper();
        RecordingDeleteMapper alertRuleMapper = new RecordingDeleteMapper();
        RecordingDeleteMapper alertHistoryMapper = new RecordingDeleteMapper();
        RecordingStatusPageConfigMapper statusPageConfigMapper = new RecordingStatusPageConfigMapper("7,42,99");
        RecordingAccountMapper accountMapper = new RecordingAccountMapper(List.of(
                new Account(1, "admin", "p", "admin@test.com", "admin", "[42,7]", new Date(), Boolean.TRUE),
                new Account(2, "user", "p", "user@test.com", "user", "[7,99]", new Date(), Boolean.TRUE)
        ));
        RecordingSseEventBus sseEventBus = new RecordingSseEventBus();
        AtomicInteger statusPageEvictCount = new AtomicInteger(0);
        StatusPageService statusPageService = (StatusPageService) Proxy.newProxyInstance(
                StatusPageService.class.getClassLoader(),
                new Class[]{StatusPageService.class},
                (proxy, method, args) -> {
                    if ("evictSummaryCache".equals(method.getName())) {
                        statusPageEvictCount.incrementAndGet();
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });

        ReflectionTestUtils.setField(service, "clientDetailMapper", detailMapper.proxy());
        ReflectionTestUtils.setField(service, "clientSshMapper", sshMapper.proxy());
        ReflectionTestUtils.setField(service, "alertRuleMapper", alertRuleMapper.proxy(AlertRuleMapper.class));
        ReflectionTestUtils.setField(service, "alertHistoryMapper", alertHistoryMapper.proxy(AlertHistoryMapper.class));
        ReflectionTestUtils.setField(service, "statusPageConfigMapper", statusPageConfigMapper.proxy());
        ReflectionTestUtils.setField(service, "accountMapper", accountMapper.proxy());
        ReflectionTestUtils.setField(service, "sseEventBus", sseEventBus);
        ReflectionTestUtils.setField(service, "statusPageService", statusPageService);

        service.deleteClient(42);

        Assertions.assertEquals(42, detailMapper.deletedId);
        Assertions.assertEquals(42, sshMapper.deletedId);
        Assertions.assertEquals(1, alertRuleMapper.deleteCount.get());
        Assertions.assertEquals(1, alertHistoryMapper.deleteCount.get());
        Assertions.assertTrue(service.removed, "client 主行应只通过 removeById 删除一次");
        Assertions.assertEquals("7,99", statusPageConfigMapper.currentClientIds,
                "状态页 CSV 应移除被删主机并保留其他有效引用");
        Assertions.assertEquals("[7]", accountMapper.rows.get(0).getClients(),
                "子账号权限 JSON 应移除被删主机");
        Assertions.assertEquals("[7,99]", accountMapper.rows.get(1).getClients(),
                "不含该主机的账号不应被改写");
        Assertions.assertEquals(1, statusPageEvictCount.get(), "状态页引用变化时应失效缓存");
        Assertions.assertEquals(1, sseEventBus.clientListCount.get(), "删除完成后应通知客户端列表刷新");
    }

    @Test
    void deleteClientShouldInvalidateTokenCacheWhenClientRowAlreadyGone() {
        Client cached = new Client(42, "host", "token-42", "cn", "node", new Date(), null);
        TestableClientServiceImpl service = new TestableClientServiceImpl(null);
        TokenLookupClientMapper mapper = new TokenLookupClientMapper(cached);
        ReflectionTestUtils.setField(service, "baseMapper", mapper.proxy());

        Assertions.assertNotNull(service.findClientByToken("token-42"), "初次 token 查询应回源并回填缓存");
        Assertions.assertEquals(1, mapper.selectOneCount.get(), "初次 token 查询应访问数据库一次");
        mapper.row = null;

        service.deleteClient(42);

        Assertions.assertNull(service.findClientByToken("token-42"),
                "数据库主行已不存在时删除仍应清理未知 token 的本地缓存");
        Assertions.assertEquals(2, mapper.selectOneCount.get(), "删除后再次 token 查询应重新回源而不是命中旧缓存");
    }

    /**
     * Build a runtime sample that satisfies the basic RuntimeDetailVO field contract.
     *
     * @param timestamp sample timestamp
     * @return runtime sample
     */
    private RuntimeDetailVO runtime(long timestamp) {
        RuntimeDetailVO vo = new RuntimeDetailVO();
        vo.setTimestamp(timestamp);
        vo.setCpuUsage(0.5);
        vo.setMemoryUsage(1.0);
        vo.setDiskUsage(2.0);
        vo.setNetworkUpload(3.0);
        vo.setNetworkDownload(4.0);
        vo.setDiskRead(5.0);
        vo.setDiskWrite(6.0);
        return vo;
    }

    private static class TestableClientServiceImpl extends ClientServiceImpl {
        private final Client existing;
        private boolean removed;

        private TestableClientServiceImpl(Client existing) {
            this.existing = existing;
        }

        @Override
        public Client getById(Serializable id) {
            return existing != null && existing.getId().equals(((Number) id).intValue()) ? existing : null;
        }

        @Override
        public boolean removeById(Serializable id) {
            removed = true;
            return true;
        }
    }

    private static class RecordingClientDetailMapper {
        private Integer deletedId;

        private ClientDetailMapper proxy() {
            return (ClientDetailMapper) Proxy.newProxyInstance(
                    ClientDetailMapper.class.getClassLoader(),
                    new Class[]{ClientDetailMapper.class},
                    (proxy, method, args) -> {
                        if ("deleteById".equals(method.getName())) {
                            deletedId = ((Number) args[0]).intValue();
                            return 1;
                        }
                        return defaultValue(method.getReturnType());
                    });
        }
    }

    private static class RecordingClientSshMapper {
        private Integer deletedId;

        private ClientSshMapper proxy() {
            return (ClientSshMapper) Proxy.newProxyInstance(
                    ClientSshMapper.class.getClassLoader(),
                    new Class[]{ClientSshMapper.class},
                    (proxy, method, args) -> {
                        if ("deleteById".equals(method.getName())) {
                            deletedId = ((Number) args[0]).intValue();
                            return 1;
                        }
                        return defaultValue(method.getReturnType());
                    });
        }
    }

    private static class RecordingDeleteMapper {
        private final AtomicInteger deleteCount = new AtomicInteger(0);

        private <T> T proxy(Class<T> mapperType) {
            return mapperType.cast(Proxy.newProxyInstance(
                    mapperType.getClassLoader(),
                    new Class[]{mapperType},
                    (proxy, method, args) -> {
                        if ("delete".equals(method.getName())) {
                            deleteCount.incrementAndGet();
                            return 1;
                        }
                        return defaultValue(method.getReturnType());
                    }));
        }
    }

    private static class RecordingStatusPageConfigMapper {
        private final StatusPageConfig row = new StatusPageConfig();
        private String currentClientIds;

        private RecordingStatusPageConfigMapper(String clientIds) {
            row.setId(1);
            row.setClientIds(clientIds);
            currentClientIds = clientIds;
        }

        private StatusPageConfigMapper proxy() {
            return (StatusPageConfigMapper) Proxy.newProxyInstance(
                    StatusPageConfigMapper.class.getClassLoader(),
                    new Class[]{StatusPageConfigMapper.class},
                    (proxy, method, args) -> {
                        if ("selectById".equals(method.getName())) {
                            return row;
                        }
                        if ("update".equals(method.getName())) {
                            currentClientIds = String.valueOf(extractSingleStringValue(args[1]));
                            row.setClientIds(currentClientIds);
                            return 1;
                        }
                        return defaultValue(method.getReturnType());
                    });
        }
    }

    private static class RecordingAccountMapper {
        private final List<Account> rows;

        private RecordingAccountMapper(List<Account> rows) {
            this.rows = new ArrayList<>(rows);
        }

        private AccountMapper proxy() {
            return (AccountMapper) Proxy.newProxyInstance(
                    AccountMapper.class.getClassLoader(),
                    new Class[]{AccountMapper.class},
                    (proxy, method, args) -> {
                        if ("selectList".equals(method.getName())) {
                            return rows;
                        }
                        if ("update".equals(method.getName())) {
                            String nextClients = String.valueOf(extractSingleStringValue(args[1]));
                            Integer accountId = extractSingleIntegerValue(args[1]);
                            if (accountId == null) {
                                accountId = rows.stream()
                                        .filter(account -> account.getClients() != null
                                                && account.getClients().contains("42"))
                                        .map(Account::getId)
                                        .findFirst()
                                        .orElse(null);
                            }
                            Integer targetAccountId = accountId;
                            rows.stream()
                                    .filter(account -> account.getId().equals(targetAccountId))
                                    .findFirst()
                                    .ifPresent(account -> account.setClients(nextClients));
                            return 1;
                        }
                        return defaultValue(method.getReturnType());
                    });
        }
    }

    private static class TokenLookupClientMapper {
        private Client row;
        private final AtomicInteger selectOneCount = new AtomicInteger(0);

        private TokenLookupClientMapper(Client row) {
            this.row = row;
        }

        private ClientMapper proxy() {
            return (ClientMapper) Proxy.newProxyInstance(
                    ClientMapper.class.getClassLoader(),
                    new Class[]{ClientMapper.class},
                    (proxy, method, args) -> {
                        if ("selectOne".equals(method.getName())) {
                            selectOneCount.incrementAndGet();
                            return row;
                        }
                        return defaultValue(method.getReturnType());
                    });
        }
    }

    private static Object extractSingleStringValue(Object wrapper) {
        if (wrapper instanceof AbstractWrapper<?, ?, ?> aw) {
            return aw.getParamNameValuePairs().values().stream()
                    .filter(String.class::isInstance)
                    .findFirst()
                    .orElse("");
        }
        return "";
    }

    private static Integer extractSingleIntegerValue(Object wrapper) {
        if (wrapper instanceof AbstractWrapper<?, ?, ?> aw) {
            for (Object value : aw.getParamNameValuePairs().values()) {
                if (value instanceof Integer i) {
                    return i;
                }
            }
        }
        return null;
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    private static class RecordingTimeSeriesAdapter implements TimeSeriesAdapter {
        private int singleWriteCount;
        private int batchWriteCount;
        private int lastBatchSize;

        @Override
        public void writeRuntime(int clientId, RuntimeDetailVO vo) {
            singleWriteCount++;
        }

        @Override
        public void writeRuntimeBatch(int clientId, List<RuntimeDetailVO> batch) {
            batchWriteCount++;
            lastBatchSize = batch.size();
        }

        @Override
        public void writeOtlpMetric(int clientId, RuntimeDetailVO vo) {
            writeRuntime(clientId, vo);
        }

        @Override
        public RuntimeHistoryVO readRuntimeHistory(int clientId, Instant from, Instant to) {
            return new RuntimeHistoryVO();
        }

        @Override
        public double[] readAvailabilityBuckets(int clientId) {
            return new double[0];
        }
    }

    private static class RecordingSseEventBus implements SseEventBus {
        private final AtomicInteger runtimeCount = new AtomicInteger(0);
        private final AtomicInteger clientListCount = new AtomicInteger(0);

        @Override
        public void publishClientList() {
            clientListCount.incrementAndGet();
        }

        @Override
        public void publishRuntime(int clientId, RuntimeDetailVO vo) {
            runtimeCount.incrementAndGet();
        }

        @Override
        public void publishAlertFired(AlertHistoryVO vo) {
            // not used in this test
        }

        @Override
        public void publishSystemdSnapshot(int clientId, SystemdSnapshotResponseVO vo) {
            // not used in this test
        }

        @Override
        public void publishSmartSnapshot(int clientId, SmartSnapshotResponseVO vo) {
            // not used in this test
        }

        @Override
        public void publishProcessSnapshot(int clientId, ProcessSnapshotResponseVO vo) {
            // not used in this test
        }

        @Override
        public void publishGpuSnapshot(int clientId, GpuSnapshotResponseVO vo) {
            // not used in this test
        }
    }
}
