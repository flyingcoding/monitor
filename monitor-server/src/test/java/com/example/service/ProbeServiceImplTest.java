package com.example.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.entity.dto.ProbeHistory;
import com.example.entity.dto.ProbeTask;
import com.example.entity.vo.request.ProbeTaskCreateVO;
import com.example.entity.vo.request.ProbeTaskUpdateVO;
import com.example.entity.vo.response.ProbeHistoryVO;
import com.example.entity.vo.response.ProbeTaskVO;
import com.example.mapper.ProbeHistoryMapper;
import com.example.mapper.ProbeTaskMapper;
import com.example.mapper.struct.ProbeStructMapper;
import com.example.service.impl.ProbeServiceImpl;
import com.example.utils.CryptoUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link ProbeServiceImpl} 单元测试。
 *
 * <p>沿用 {@code OidcProviderServiceImplTest} / {@code AlertEvaluatorImplTest} 同款 JDK 动态代理桩风格，
 * 避免依赖 Mockito 的 attach 机制；用真实 {@link CryptoUtils} 保证加解密往返。
 */
class ProbeServiceImplTest {

    private ProbeServiceImpl service;
    private final List<ProbeTask> taskRows = new ArrayList<>();
    private final List<ProbeHistory> historyRows = new ArrayList<>();
    private final AtomicLong taskIdSeq = new AtomicLong(1L);
    private final AtomicLong historyIdSeq = new AtomicLong(1L);
    private CryptoUtils cryptoUtils;
    private ProbeStructMapper structMapper;
    private ProbeHistoryMapper historyMapper;

    @BeforeEach
    void setUp() {
        taskRows.clear();
        historyRows.clear();
        taskIdSeq.set(1L);
        historyIdSeq.set(1L);

        String base64Key = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        cryptoUtils = new CryptoUtils(base64Key);

        // 不使用真实 MapStruct 实现（spring 编译期生成的 implementation 在测试中需 ApplicationContext），
        // 改用 JDK 动态代理：toTaskVO 用 BeanUtils 风格手抄字段；toHistoryVO 同理
        structMapper = (ProbeStructMapper) Proxy.newProxyInstance(
                ProbeStructMapper.class.getClassLoader(),
                new Class[]{ProbeStructMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "toTaskVO" -> {
                        ProbeTask t = (ProbeTask) args[0];
                        ProbeTaskVO vo = new ProbeTaskVO();
                        vo.setId(t.getId());
                        vo.setName(t.getName());
                        vo.setType(t.getType());
                        vo.setTarget(t.getTarget());
                        vo.setIntervalSec(t.getIntervalSec());
                        vo.setTimeoutSec(t.getTimeoutSec());
                        vo.setExpectedStatusCode(t.getExpectedStatusCode());
                        vo.setExpectedBodyPattern(t.getExpectedBodyPattern());
                        vo.setBasicAuthUsername(t.getBasicAuthUsername());
                        vo.setSslWarnDays(t.getSslWarnDays());
                        vo.setConsecutiveFailuresThreshold(t.getConsecutiveFailuresThreshold());
                        vo.setEnabled(t.getEnabled());
                        vo.setCreatedAt(t.getCreatedAt());
                        vo.setUpdatedAt(t.getUpdatedAt());
                        yield vo;
                    }
                    case "toHistoryVO" -> {
                        ProbeHistory h = (ProbeHistory) args[0];
                        ProbeHistoryVO vo = new ProbeHistoryVO();
                        vo.setId(h.getId());
                        vo.setTaskId(h.getTaskId());
                        vo.setExecutedAt(h.getExecutedAt());
                        vo.setSuccess(h.getSuccess());
                        vo.setLatencyMs(h.getLatencyMs());
                        vo.setStatusCode(h.getStatusCode());
                        vo.setSslDaysRemaining(h.getSslDaysRemaining());
                        vo.setErrorMessage(h.getErrorMessage());
                        yield vo;
                    }
                    default -> defaultProxyReturn(proxy, method, args, "ProbeStructMapperStub");
                });

        // ProbeHistoryMapper stub：handle insert / delete / selectPage
        historyMapper = (ProbeHistoryMapper) Proxy.newProxyInstance(
                ProbeHistoryMapper.class.getClassLoader(),
                new Class[]{ProbeHistoryMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "insert" -> {
                        ProbeHistory h = (ProbeHistory) args[0];
                        h.setId(historyIdSeq.getAndIncrement());
                        historyRows.add(h);
                        yield 1;
                    }
                    case "delete" -> {
                        // 测试不依赖具体 wrapper 解析；简化为"删除所有"
                        int count = historyRows.size();
                        historyRows.clear();
                        yield count;
                    }
                    case "selectPage" -> {
                        @SuppressWarnings("unchecked")
                        IPage<ProbeHistory> p = (IPage<ProbeHistory>) args[0];
                        p.setRecords(new ArrayList<>(historyRows));
                        p.setTotal(historyRows.size());
                        yield p;
                    }
                    default -> defaultProxyReturn(proxy, method, args, "ProbeHistoryMapperStub");
                });

        service = spyWithInMemoryRows();
        ReflectionTestUtils.setField(service, "cryptoUtils", cryptoUtils);
        ReflectionTestUtils.setField(service, "probeStructMapper", structMapper);
        ReflectionTestUtils.setField(service, "probeHistoryMapper", historyMapper);
    }

    /**
     * 内存版 ProbeServiceImpl：让 list / save / getById / updateById / removeById 落到 List。
     */
    private ProbeServiceImpl spyWithInMemoryRows() {
        ProbeServiceImpl spy = new ProbeServiceImpl() {
            @Override
            public List<ProbeTask> list(Wrapper<ProbeTask> queryWrapper) {
                // 不解析 wrapper；测试用例靠 taskRows 的顺序
                return new ArrayList<>(taskRows);
            }

            @Override
            public List<ProbeTask> list() {
                return new ArrayList<>(taskRows);
            }

            @Override
            public ProbeTask getById(Serializable id) {
                long lid = ((Number) id).longValue();
                for (ProbeTask t : taskRows) {
                    if (t.getId() != null && t.getId() == lid) {
                        return t;
                    }
                }
                return null;
            }

            @Override
            public boolean save(ProbeTask entity) {
                if (entity.getId() == null) {
                    entity.setId(taskIdSeq.getAndIncrement());
                }
                taskRows.add(entity);
                return true;
            }

            @Override
            public boolean updateById(ProbeTask entity) {
                for (int i = 0; i < taskRows.size(); i++) {
                    if (taskRows.get(i).getId().equals(entity.getId())) {
                        taskRows.set(i, entity);
                        return true;
                    }
                }
                return false;
            }

            @Override
            public boolean removeById(Serializable id) {
                long lid = ((Number) id).longValue();
                return taskRows.removeIf(t -> t.getId() != null && t.getId() == lid);
            }
        };

        // baseMapper stub：selectCount 用于 nameExists；返回 0 让所有 create / update 通过，
        // 测试单独的"重复名"用例时再用专属逻辑。
        ProbeTaskMapper baseMapper = (ProbeTaskMapper) Proxy.newProxyInstance(
                ProbeTaskMapper.class.getClassLoader(),
                new Class[]{ProbeTaskMapper.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "selectCount" -> 0L;
                    default -> defaultProxyReturn(proxy, method, args, "ProbeTaskMapperStub");
                });
        ReflectionTestUtils.setField(spy, "baseMapper", baseMapper);
        return spy;
    }

    /**
     * 给 baseMapper 注入"按 name 计数 rows"的语义，模拟数据库的唯一约束检查。
     */
    private void installRowAwareNameCounter() {
        ProbeTaskMapper baseMapper = (ProbeTaskMapper) Proxy.newProxyInstance(
                ProbeTaskMapper.class.getClassLoader(),
                new Class[]{ProbeTaskMapper.class},
                (proxy, method, args) -> {
                    if ("selectCount".equals(method.getName())) {
                        @SuppressWarnings("unchecked")
                        Wrapper<ProbeTask> w = (Wrapper<ProbeTask>) args[0];
                        String segment = w == null ? "" : w.getSqlSegment();
                        // 当 wrapper 含 "name" 表达式时按 rows 中是否存在同名行返回计数
                        if (segment != null && segment.toLowerCase().contains("name")) {
                            return (long) taskRows.size(); // 调用方判断 > 0 即认为存在
                        }
                        return 0L;
                    }
                    return defaultProxyReturn(proxy, method, args, "ProbeTaskMapperStub");
                });
        ReflectionTestUtils.setField(service, "baseMapper", baseMapper);
    }

    /**
     * 创建：必填字段写入，headers JSON 加密，basic_auth_password 加密，channel_ids 序列化为 JSON。
     */
    @Test
    void createShouldEncryptHeadersAndPasswordAndSerializeChannelIds() {
        ProbeTaskCreateVO vo = baseCreateVO("http-api");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer abc-123");
        headers.put("X-Token", "secret");
        vo.setHeaders(headers);
        vo.setBasicAuthUsername("alice");
        vo.setBasicAuthPassword("p@ss");
        vo.setChannelIds(List.of(11L, 22L));

        ProbeTaskVO result = service.create(vo);

        Assertions.assertNotNull(result.getId());
        Assertions.assertEquals("http-api", result.getName());
        Assertions.assertEquals(Boolean.TRUE, result.getHasBasicAuthPassword());
        // headers 在 VO 中已脱敏为 ***
        Assertions.assertEquals(2, result.getHeaders().size());
        for (String v : result.getHeaders().values()) {
            Assertions.assertEquals(ProbeServiceImpl.MASK_PLACEHOLDER, v);
        }
        Assertions.assertEquals(List.of(11L, 22L), result.getChannelIds());

        ProbeTask stored = taskRows.get(0);
        // headers 密文以 ENC: 开头
        Assertions.assertNotNull(stored.getHeadersEnc());
        Assertions.assertTrue(stored.getHeadersEnc().startsWith("ENC:"));
        // basic_auth_password 加密
        Assertions.assertTrue(stored.getBasicAuthPasswordEnc().startsWith("ENC:"));
        // channel_ids 是 JSON 数组字符串
        Assertions.assertEquals("[11,22]", stored.getChannelIds());

        // resolveHeaders 能往返回原文
        Map<String, String> decrypted = service.resolveHeaders(stored);
        Assertions.assertEquals("Bearer abc-123", decrypted.get("Authorization"));
        Assertions.assertEquals("secret", decrypted.get("X-Token"));
        Assertions.assertEquals("p@ss", service.resolveBasicAuthPassword(stored));
    }

    /**
     * 创建：headers 为 null 时 headersEnc 不写入。
     */
    @Test
    void createWithoutHeadersShouldLeaveColumnNull() {
        ProbeTaskCreateVO vo = baseCreateVO("plain-http");
        vo.setHeaders(null);
        vo.setBasicAuthPassword(null);
        service.create(vo);

        ProbeTask stored = taskRows.get(0);
        Assertions.assertNull(stored.getHeadersEnc());
        Assertions.assertNull(stored.getBasicAuthPasswordEnc());
    }

    /**
     * 重复名称应抛 409 ResponseStatusException 不写入。
     */
    @Test
    void createDuplicateNameShouldReturnConflict() {
        // 先插入一条
        ProbeTaskCreateVO first = baseCreateVO("uniq-name");
        service.create(first);
        installRowAwareNameCounter();

        ProbeTaskCreateVO second = baseCreateVO("uniq-name");
        ResponseStatusException ex = Assertions.assertThrows(ResponseStatusException.class,
                () -> service.create(second));
        Assertions.assertEquals(org.springframework.http.HttpStatus.CONFLICT, ex.getStatusCode());
        Assertions.assertEquals(1, taskRows.size());
    }

    /**
     * 更新：basic_auth_password = "***" 沿用旧密文。
     */
    @Test
    void updateWithMaskedPasswordShouldKeepExisting() {
        ProbeTaskCreateVO vo = baseCreateVO("api1");
        vo.setBasicAuthPassword("orig-pwd");
        ProbeTaskVO created = service.create(vo);
        String origEnc = taskRows.get(0).getBasicAuthPasswordEnc();
        Assertions.assertNotNull(origEnc);

        ProbeTaskUpdateVO upd = baseUpdateVO("api1");
        upd.setBasicAuthPassword(ProbeServiceImpl.MASK_PLACEHOLDER);
        service.update(created.getId(), upd);

        Assertions.assertEquals(origEnc, taskRows.get(0).getBasicAuthPasswordEnc(),
                "*** 占位应使密文保持不变");
    }

    /**
     * 更新：basic_auth_password = "" 清空。
     */
    @Test
    void updateWithEmptyPasswordShouldClear() {
        ProbeTaskCreateVO vo = baseCreateVO("api2");
        vo.setBasicAuthPassword("orig");
        ProbeTaskVO created = service.create(vo);
        Assertions.assertNotNull(taskRows.get(0).getBasicAuthPasswordEnc());

        ProbeTaskUpdateVO upd = baseUpdateVO("api2");
        upd.setBasicAuthPassword("");
        service.update(created.getId(), upd);

        Assertions.assertNull(taskRows.get(0).getBasicAuthPasswordEnc());
    }

    /**
     * 更新：headers 中 value 为 "***" 时用旧值回填。
     */
    @Test
    void updateWithMaskedHeaderShouldMergeFromOld() {
        ProbeTaskCreateVO vo = baseCreateVO("api3");
        Map<String, String> hdrs = new LinkedHashMap<>();
        hdrs.put("Authorization", "Bearer original");
        hdrs.put("X-Trace", "abc");
        vo.setHeaders(hdrs);
        ProbeTaskVO created = service.create(vo);

        // 编辑：Authorization 保持 ***（不修改），X-Trace 更新为新值
        Map<String, String> newHdrs = new LinkedHashMap<>();
        newHdrs.put("Authorization", ProbeServiceImpl.MASK_PLACEHOLDER);
        newHdrs.put("X-Trace", "xyz");
        ProbeTaskUpdateVO upd = baseUpdateVO("api3");
        upd.setHeaders(newHdrs);
        service.update(created.getId(), upd);

        Map<String, String> decrypted = service.resolveHeaders(taskRows.get(0));
        Assertions.assertEquals("Bearer original", decrypted.get("Authorization"));
        Assertions.assertEquals("xyz", decrypted.get("X-Trace"));
    }

    /**
     * 更新：headers = null 保留旧密文。
     */
    @Test
    void updateWithNullHeadersShouldKeepExisting() {
        ProbeTaskCreateVO vo = baseCreateVO("api4");
        Map<String, String> hdrs = Map.of("Authorization", "Bearer x");
        vo.setHeaders(hdrs);
        ProbeTaskVO created = service.create(vo);
        String origEnc = taskRows.get(0).getHeadersEnc();

        ProbeTaskUpdateVO upd = baseUpdateVO("api4");
        upd.setHeaders(null);
        service.update(created.getId(), upd);

        Assertions.assertEquals(origEnc, taskRows.get(0).getHeadersEnc());
    }

    /**
     * 更新：headers = 空 Map 清空。
     */
    @Test
    void updateWithEmptyHeadersShouldClear() {
        ProbeTaskCreateVO vo = baseCreateVO("api5");
        vo.setHeaders(Map.of("Authorization", "Bearer x"));
        ProbeTaskVO created = service.create(vo);

        ProbeTaskUpdateVO upd = baseUpdateVO("api5");
        upd.setHeaders(new LinkedHashMap<>());
        service.update(created.getId(), upd);

        Assertions.assertNull(taskRows.get(0).getHeadersEnc());
    }

    /**
     * resolveChannelIds 在 JSON 不合法时返回空 List 不抛异常。
     */
    @Test
    void resolveChannelIdsHandlesInvalidJson() {
        ProbeTask t = new ProbeTask();
        t.setChannelIds("not-a-json");
        Assertions.assertTrue(service.resolveChannelIds(t).isEmpty());

        t.setChannelIds(null);
        Assertions.assertTrue(service.resolveChannelIds(t).isEmpty());

        t.setChannelIds("[1,2,3]");
        Assertions.assertEquals(List.of(1L, 2L, 3L), service.resolveChannelIds(t));
    }

    /**
     * delete 不存在 ID 返回 false。
     */
    @Test
    void deleteMissingShouldReturnFalse() {
        Assertions.assertFalse(service.delete(999L));
    }

    /**
     * delete 已存在的任务返回 true 并从 list 中移除。
     */
    @Test
    void deleteExistingShouldRemove() {
        ProbeTaskCreateVO vo = baseCreateVO("api6");
        ProbeTaskVO created = service.create(vo);
        Assertions.assertTrue(service.delete(created.getId()));
        Assertions.assertEquals(0, taskRows.size());
    }

    /**
     * saveHistory + deleteHistoryBefore：写一条历史后能删除全部历史（mock 实现）。
     */
    @Test
    void historyLifecycleShouldRoundTrip() {
        ProbeHistory h = new ProbeHistory();
        h.setTaskId(1L);
        h.setExecutedAt(new Date());
        h.setSuccess(Boolean.TRUE);
        h.setLatencyMs(123);
        Assertions.assertTrue(service.saveHistory(h));
        Assertions.assertEquals(1, historyRows.size());

        int deleted = service.deleteHistoryBefore(new Date());
        Assertions.assertEquals(1, deleted);
        Assertions.assertEquals(0, historyRows.size());
    }

    /**
     * listAll 按 id 倒序展开为 VO 列表。
     */
    @Test
    void listAllShouldReturnVOs() {
        service.create(baseCreateVO("a"));
        service.create(baseCreateVO("b"));
        List<ProbeTaskVO> vos = service.listAll();
        Assertions.assertEquals(2, vos.size());
        for (ProbeTaskVO vo : vos) {
            Assertions.assertNotNull(vo.getId());
            Assertions.assertNotNull(vo.getName());
        }
    }

    /**
     * resolveBasicAuthPassword 返回 null 在密文缺失时。
     */
    @Test
    void resolveBasicAuthPasswordHandlesMissing() {
        ProbeTask t = new ProbeTask();
        Assertions.assertNull(service.resolveBasicAuthPassword(t));
        t.setBasicAuthPasswordEnc(" ");
        Assertions.assertNull(service.resolveBasicAuthPassword(t));
    }

    /**
     * listEnabledTasks 返回所有 rows（test stub list() 不解析 wrapper）。
     */
    @Test
    void listEnabledTasksShouldReturnRows() {
        ProbeTaskCreateVO vo = baseCreateVO("enabled-task");
        service.create(vo);
        List<ProbeTask> enabled = service.listEnabledTasks();
        Assertions.assertEquals(1, enabled.size());
    }

    /**
     * listHistory 触发分页查询路径并返回 IPage。
     */
    @Test
    void listHistoryShouldQueryAndConvert() {
        ProbeHistory h = new ProbeHistory();
        h.setTaskId(50L);
        h.setExecutedAt(new Date());
        h.setSuccess(Boolean.TRUE);
        service.saveHistory(h);

        IPage<ProbeHistoryVO> page = service.listHistory(50L, 1, 20);
        Assertions.assertEquals(1, page.getTotal());
        Assertions.assertEquals(1, page.getRecords().size());
        Assertions.assertEquals(50L, page.getRecords().get(0).getTaskId());
    }

    /**
     * listHistory 参数非法时抛 400。
     */
    @Test
    void listHistoryShouldRejectInvalidPagination() {
        Assertions.assertThrows(ResponseStatusException.class, () -> service.listHistory(1L, 0, 20));
        Assertions.assertThrows(ResponseStatusException.class, () -> service.listHistory(1L, 1, 0));
        Assertions.assertThrows(ResponseStatusException.class, () -> service.listHistory(1L, 1, 201));
        Assertions.assertThrows(ResponseStatusException.class, () -> service.listHistory(null, 1, 20));
    }

    /**
     * update 不存在的 ID 抛 404。
     */
    @Test
    void updateMissingShouldReturnNotFound() {
        ProbeTaskUpdateVO upd = baseUpdateVO("ghost");
        ResponseStatusException ex = Assertions.assertThrows(ResponseStatusException.class,
                () -> service.update(9999L, upd));
        Assertions.assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ===== helpers =====

    private ProbeTaskCreateVO baseCreateVO(String name) {
        ProbeTaskCreateVO vo = new ProbeTaskCreateVO();
        vo.setName(name);
        vo.setType("http");
        vo.setTarget("https://example.com/health");
        vo.setIntervalSec(60);
        vo.setTimeoutSec(10);
        vo.setConsecutiveFailuresThreshold(2);
        vo.setEnabled(Boolean.TRUE);
        vo.setSslWarnDays(30);
        return vo;
    }

    private ProbeTaskUpdateVO baseUpdateVO(String name) {
        ProbeTaskUpdateVO vo = new ProbeTaskUpdateVO();
        vo.setName(name);
        vo.setType("http");
        vo.setTarget("https://example.com/health");
        vo.setIntervalSec(60);
        vo.setTimeoutSec(10);
        vo.setConsecutiveFailuresThreshold(2);
        vo.setEnabled(Boolean.TRUE);
        vo.setSslWarnDays(30);
        return vo;
    }

    private Object defaultProxyReturn(Object proxy, Method method, Object[] args, String label) {
        if ("toString".equals(method.getName())) return label;
        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
        if ("equals".equals(method.getName())) return proxy == args[0];
        Class<?> ret = method.getReturnType();
        if (ret == boolean.class) return Boolean.FALSE;
        if (ret == int.class) return 0;
        if (ret == long.class) return 0L;
        if (ret == double.class) return 0.0;
        return null;
    }
}
