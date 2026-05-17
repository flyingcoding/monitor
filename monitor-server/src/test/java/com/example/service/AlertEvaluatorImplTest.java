package com.example.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.config.SseEventBus;
import com.example.entity.alert.AlertEvent;
import com.example.entity.alert.AlertLevel;
import com.example.entity.alert.AlertStatus;
import com.example.entity.dto.AlertHistory;
import com.example.entity.dto.AlertRule;
import com.example.entity.dto.Client;
import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.entity.vo.response.AlertHistoryVO;
import com.example.mapper.struct.AlertStructMapper;
import com.example.service.impl.AlertEvaluatorImpl;
import com.example.service.impl.AlertWindowCache;
import com.example.utils.Const;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AlertEvaluatorImpl 单元测试。
 * <p>
 * 项目惯例：使用 JDK 动态代理替代 Mockito（避免依赖 JVM attach 能力，见 ClientControllerTest）。
 * 通过反射注入 stub 到 evaluator 字段，并直接驱动同步 {@code evaluate} 方法（绕过 @Async 代理）。
 */
class AlertEvaluatorImplTest {

    private AlertEvaluatorImpl evaluator;
    private AlertWindowCache windowCache;

    // stub 内部可变状态（在并发用例下也需要线程安全；多数用例为单线程，并发用例显式注释）
    private final List<AlertRule> rulesInDb = new ArrayList<>();
    private final List<AlertHistory> historyInDb = new ArrayList<>();
    private final ConcurrentLinkedQueue<Object> publishedMessages = new ConcurrentLinkedQueue<>();
    private final List<AlertHistoryVO> publishedSseAlerts = new ArrayList<>();
    private final AtomicReference<Long> historyIdSequence = new AtomicReference<>(1L);

    /**
     * 装配 evaluator 与所有桩对象。
     */
    @BeforeEach
    void setUp() {
        rulesInDb.clear();
        historyInDb.clear();
        publishedMessages.clear();
        publishedSseAlerts.clear();
        historyIdSequence.set(1L);

        evaluator = new AlertEvaluatorImpl();
        windowCache = new AlertWindowCache();

        AlertRuleService alertRuleService = stubAlertRuleService();
        AlertHistoryService alertHistoryService = stubAlertHistoryService();
        AmqpTemplate rabbitTemplate = stubRabbitTemplate();
        ClientService clientService = stubClientService();
        SseEventBus sseEventBus = stubSseEventBus();
        AlertStructMapper alertStructMapper = stubAlertStructMapper();

        ReflectionTestUtils.setField(evaluator, "alertRuleService", alertRuleService);
        ReflectionTestUtils.setField(evaluator, "alertHistoryService", alertHistoryService);
        ReflectionTestUtils.setField(evaluator, "rabbitTemplate", rabbitTemplate);
        ReflectionTestUtils.setField(evaluator, "clientService", clientService);
        ReflectionTestUtils.setField(evaluator, "windowCache", windowCache);
        ReflectionTestUtils.setField(evaluator, "sseEventBus", sseEventBus);
        ReflectionTestUtils.setField(evaluator, "alertStructMapper", alertStructMapper);
    }

    /**
     * 持续超过阈值满足 durationSec 时应触发告警，产生 firing 历史并投通知。
     */
    @Test
    void should_fire_when_continuously_above_threshold() throws Exception {
        rulesInDb.add(buildRule(1L, 100, "cpu", "gt", 0.8, 2));

        // 第一次评估：满足阈值，窗口跨度不够 → 不触发
        evaluator.evaluate(100, runtime(0.9));
        Assertions.assertEquals(0, historyInDb.size());

        // 等到窗口跨度 >= 2 秒
        Thread.sleep(2100);
        evaluator.evaluate(100, runtime(0.91));

        // 应触发：写入 history + 发 RabbitMQ
        Assertions.assertEquals(1, historyInDb.size());
        AlertHistory fired = historyInDb.get(0);
        Assertions.assertEquals(AlertStatus.FIRING.getColumn(), fired.getStatus());
        Assertions.assertEquals(100, fired.getClientId());
        Assertions.assertEquals(AlertLevel.WARNING.getColumn(), fired.getLevel());

        Assertions.assertEquals(1, publishedMessages.size());
        Object firstMsg = publishedMessages.peek();
        Assertions.assertInstanceOf(AlertEvent.class, firstMsg);
        AlertEvent event = (AlertEvent) firstMsg;
        Assertions.assertEquals(Long.valueOf(1L), event.getRuleId());
        Assertions.assertEquals(Integer.valueOf(100), event.getClientId());

        // SSE 推送应紧随 RabbitMQ 投递，载荷为 AlertHistoryVO
        Assertions.assertEquals(1, publishedSseAlerts.size(), "应通过 SseEventBus 推送一条 alert-fired 事件");
        AlertHistoryVO sseVo = publishedSseAlerts.get(0);
        Assertions.assertEquals(Integer.valueOf(100), sseVo.getClientId());
        Assertions.assertEquals(Long.valueOf(1L), sseVo.getRuleId());
        Assertions.assertEquals(AlertStatus.FIRING.getColumn(), sseVo.getStatus());
        Assertions.assertEquals("test-rule-1", sseVo.getRuleName());
    }

    /**
     * 在阈值上下波动（met / not-met 交错）时不应触发告警。
     */
    @Test
    void should_not_fire_when_intermittent() throws Exception {
        rulesInDb.add(buildRule(2L, 200, "cpu", "gt", 0.8, 2));

        evaluator.evaluate(200, runtime(0.9));     // met
        Thread.sleep(500);
        evaluator.evaluate(200, runtime(0.5));     // not met, 打断窗口
        Thread.sleep(500);
        evaluator.evaluate(200, runtime(0.9));     // met
        Thread.sleep(1500);
        evaluator.evaluate(200, runtime(0.5));     // not met
        Thread.sleep(500);
        evaluator.evaluate(200, runtime(0.95));    // met

        Assertions.assertEquals(0, historyInDb.size(), "波动样本不应触发告警");
        Assertions.assertEquals(0, publishedMessages.size());
    }

    /**
     * 之前 firing 后，持续低于阈值满足 durationSec 时应自动 resolved。
     */
    @Test
    void should_resolve_when_continuously_below_threshold() throws Exception {
        rulesInDb.add(buildRule(3L, 300, "cpu", "gt", 0.8, 1));

        // 制造一条 firing 历史
        AlertHistory firing = new AlertHistory();
        firing.setId(99L);
        firing.setRuleId(3L);
        firing.setClientId(300);
        firing.setStatus(AlertStatus.FIRING.getColumn());
        firing.setLevel(AlertLevel.WARNING.getColumn());
        firing.setFiredAt(new Date(System.currentTimeMillis() - 5000));
        historyInDb.add(firing);

        // 推入两次不满足条件的样本，间隔 > 1 秒
        evaluator.evaluate(300, runtime(0.5));
        Thread.sleep(1100);
        evaluator.evaluate(300, runtime(0.6));

        Assertions.assertEquals(AlertStatus.RESOLVED.getColumn(), firing.getStatus());
        Assertions.assertNotNull(firing.getResolvedAt());
    }

    /**
     * 规则的 silence_until 未到期时应跳过评估，不触发也不写历史。
     */
    @Test
    void should_skip_silenced_rule() throws Exception {
        AlertRule rule = buildRule(4L, 400, "cpu", "gt", 0.8, 1);
        rule.setSilenceUntil(new Date(System.currentTimeMillis() + 60_000));
        rulesInDb.add(rule);

        evaluator.evaluate(400, runtime(0.99));
        Thread.sleep(1100);
        evaluator.evaluate(400, runtime(0.99));

        Assertions.assertEquals(0, historyInDb.size());
        Assertions.assertEquals(0, publishedMessages.size());
    }

    /**
     * 已有 firing 状态的历史时，不重复发火（避免抖动放大）。
     */
    @Test
    void should_not_duplicate_fire() throws Exception {
        rulesInDb.add(buildRule(5L, 500, "cpu", "gt", 0.8, 1));

        // 预置一条 firing 历史
        AlertHistory existing = new AlertHistory();
        existing.setId(77L);
        existing.setRuleId(5L);
        existing.setClientId(500);
        existing.setStatus(AlertStatus.FIRING.getColumn());
        existing.setLevel(AlertLevel.WARNING.getColumn());
        existing.setFiredAt(new Date(System.currentTimeMillis() - 5000));
        historyInDb.add(existing);

        evaluator.evaluate(500, runtime(0.95));
        Thread.sleep(1100);
        evaluator.evaluate(500, runtime(0.95));

        // 不应新增历史，也不应再次发通知
        Assertions.assertEquals(1, historyInDb.size());
        Assertions.assertEquals(0, publishedMessages.size());
    }

    /**
     * 用户确认（acknowledged）后但条件仍未恢复时：评估器视 acknowledged 为活跃，
     * 不应重复创建 firing 历史，也不应再次投 RabbitMQ。
     */
    @Test
    void should_not_duplicate_fire_when_acknowledged_and_above_threshold() throws Exception {
        rulesInDb.add(buildRule(6L, 600, "cpu", "gt", 0.8, 1));

        // 预置一条 acknowledged 历史（用户已确认但故障未恢复）
        AlertHistory acked = new AlertHistory();
        acked.setId(66L);
        acked.setRuleId(6L);
        acked.setClientId(600);
        acked.setStatus(AlertStatus.ACKNOWLEDGED.getColumn());
        acked.setLevel(AlertLevel.WARNING.getColumn());
        acked.setFiredAt(new Date(System.currentTimeMillis() - 10_000));
        acked.setAckedAt(new Date(System.currentTimeMillis() - 5000));
        acked.setAckedBy(1);
        historyInDb.add(acked);

        // 持续超阈值
        evaluator.evaluate(600, runtime(0.95));
        Thread.sleep(1100);
        evaluator.evaluate(600, runtime(0.95));

        // 关键断言：acknowledged 已经作为活跃告警占位，不应新增 firing 历史，也不发 MQ
        Assertions.assertEquals(1, historyInDb.size(), "acknowledged 占位时不应新增 firing 历史");
        Assertions.assertEquals(AlertStatus.ACKNOWLEDGED.getColumn(), historyInDb.get(0).getStatus());
        Assertions.assertEquals(0, publishedMessages.size(), "已 acknowledged 时不应重复投 RabbitMQ");
    }

    /**
     * 用户确认后条件持续恢复时：evaluator 应将 acknowledged 历史自动 resolve，
     * 不能让 acknowledged 状态永远停留。
     */
    @Test
    void should_resolve_acknowledged_when_continuously_below_threshold() throws Exception {
        rulesInDb.add(buildRule(7L, 700, "cpu", "gt", 0.8, 1));

        AlertHistory acked = new AlertHistory();
        acked.setId(88L);
        acked.setRuleId(7L);
        acked.setClientId(700);
        acked.setStatus(AlertStatus.ACKNOWLEDGED.getColumn());
        acked.setLevel(AlertLevel.WARNING.getColumn());
        acked.setFiredAt(new Date(System.currentTimeMillis() - 10_000));
        acked.setAckedAt(new Date(System.currentTimeMillis() - 5000));
        acked.setAckedBy(1);
        historyInDb.add(acked);

        evaluator.evaluate(700, runtime(0.4));
        Thread.sleep(1100);
        evaluator.evaluate(700, runtime(0.3));

        Assertions.assertEquals(AlertStatus.RESOLVED.getColumn(), acked.getStatus(),
                "acknowledged 状态在条件持续恢复时应被自动 resolved");
        Assertions.assertNotNull(acked.getResolvedAt(), "resolved_at 应被填写");
    }

    /**
     * 虚拟线程并发评估同一 (ruleId, clientId)：
     * - 不应抛 ConcurrentModificationException 等竞态异常
     * - 最终 firing 历史最多一条（同一活跃告警不被重复创建）
     */
    @Test
    void should_be_thread_safe_under_concurrent_evaluation() throws Exception {
        rulesInDb.add(buildRule(8L, 800, "cpu", "gt", 0.8, 1));
        // 预热窗口：保证后续并发评估中窗口跨度足够，能进入触发分支
        evaluator.evaluate(800, runtime(0.95));
        Thread.sleep(1100);

        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        // 半数超阈值半数不超，模拟并发竞态
                        double cpu = idx % 2 == 0 ? 0.95 : 0.4;
                        evaluator.evaluate(800, runtime(cpu));
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            Assertions.assertTrue(done.await(10, TimeUnit.SECONDS), "并发任务应在 10s 内完成");
        } finally {
            pool.shutdownNow();
        }

        Assertions.assertTrue(errors.isEmpty(),
                "并发评估不应抛任何异常，实际抛出: " + errors.stream()
                        .map(t -> t.getClass().getSimpleName() + ": " + t.getMessage())
                        .toList());

        // 至多 1 条 firing 历史；resolve 路径可能把其状态改成 resolved，因此校验"活跃 firing 不超过 1"
        long firingCount = historyInDb.stream()
                .filter(h -> AlertStatus.FIRING.getColumn().equals(h.getStatus()))
                .count();
        Assertions.assertTrue(firingCount <= 1,
                "并发下最多只能创建 1 条 firing 历史，实际 " + firingCount);
        Assertions.assertTrue(historyInDb.size() <= 1,
                "并发下最多只能创建 1 条历史记录，实际 " + historyInDb.size());
    }

    // ====== helpers ======

    /**
     * 构造测试规则。
     */
    private AlertRule buildRule(Long id, Integer clientId, String metric, String operator,
                                Double threshold, Integer durationSec) {
        AlertRule rule = new AlertRule();
        rule.setId(id);
        rule.setName("test-rule-" + id);
        rule.setClientId(clientId);
        rule.setMetric(metric);
        rule.setOperator(operator);
        rule.setThreshold(threshold);
        rule.setDurationSec(durationSec);
        rule.setLevel(AlertLevel.WARNING.getColumn());
        rule.setEnabled(true);
        return rule;
    }

    /**
     * 构造测试 runtime。
     */
    private RuntimeDetailVO runtime(double cpu) {
        RuntimeDetailVO vo = new RuntimeDetailVO();
        ReflectionTestUtils.setField(vo, "timestamp", System.currentTimeMillis());
        ReflectionTestUtils.setField(vo, "cpuUsage", cpu);
        ReflectionTestUtils.setField(vo, "memoryUsage", 0.0);
        ReflectionTestUtils.setField(vo, "diskUsage", 0.0);
        ReflectionTestUtils.setField(vo, "networkUpload", 0.0);
        ReflectionTestUtils.setField(vo, "networkDownload", 0.0);
        ReflectionTestUtils.setField(vo, "diskRead", 0.0);
        ReflectionTestUtils.setField(vo, "diskWrite", 0.0);
        return vo;
    }

    /**
     * 构造 AlertRuleService stub。
     */
    private AlertRuleService stubAlertRuleService() {
        return (AlertRuleService) Proxy.newProxyInstance(
                AlertRuleService.class.getClassLoader(),
                new Class[]{AlertRuleService.class},
                (proxy, method, args) -> {
                    if ("list".equals(method.getName())
                            && method.getParameterTypes().length == 1
                            && Wrapper.class.isAssignableFrom(method.getParameterTypes()[0])) {
                        return new ArrayList<>(rulesInDb);
                    }
                    return defaultProxyMethod(proxy, method, args, "AlertRuleServiceStub");
                });
    }

    /**
     * 构造 AlertHistoryService stub。
     * <p>
     * getOne 模拟 AlertEvaluator 的"查最新活跃历史"逻辑：从尾向头扫描，命中状态为
     * firing 或 acknowledged 的最近一条。save/updateById 模拟成功路径并写入内存 List。
     */
    private AlertHistoryService stubAlertHistoryService() {
        Set<String> activeStatuses = Set.of(
                AlertStatus.FIRING.getColumn(),
                AlertStatus.ACKNOWLEDGED.getColumn());
        return (AlertHistoryService) Proxy.newProxyInstance(
                AlertHistoryService.class.getClassLoader(),
                new Class[]{AlertHistoryService.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("save".equals(name) && args[0] instanceof AlertHistory h) {
                        synchronized (historyInDb) {
                            Long id = historyIdSequence.getAndUpdate(v -> v + 1);
                            h.setId(id);
                            historyInDb.add(h);
                        }
                        return Boolean.TRUE;
                    }
                    if ("getOne".equals(name)) {
                        // 评估器调用 getOne 查"活跃历史"（firing OR acknowledged），桩按相同语义返回
                        synchronized (historyInDb) {
                            for (int i = historyInDb.size() - 1; i >= 0; i--) {
                                AlertHistory h = historyInDb.get(i);
                                if (activeStatuses.contains(h.getStatus())) {
                                    return h;
                                }
                            }
                        }
                        return null;
                    }
                    if ("updateById".equals(name) && args[0] instanceof AlertHistory h) {
                        Assertions.assertNotNull(h.getId());
                        return Boolean.TRUE;
                    }
                    return defaultProxyMethod(proxy, method, args, "AlertHistoryServiceStub");
                });
    }

    /**
     * 构造 AmqpTemplate stub。
     */
    private AmqpTemplate stubRabbitTemplate() {
        return (AmqpTemplate) Proxy.newProxyInstance(
                AmqpTemplate.class.getClassLoader(),
                new Class[]{AmqpTemplate.class},
                (proxy, method, args) -> {
                    if ("convertAndSend".equals(method.getName())
                            && args.length >= 2
                            && Const.MQ_NOTIFICATION.equals(args[0])) {
                        publishedMessages.add(args[1]);
                        return null;
                    }
                    return defaultProxyMethod(proxy, method, args, "AmqpTemplateStub");
                });
    }

    /**
     * 构造 ClientService stub（仅满足 findClientById）。
     */
    private ClientService stubClientService() {
        return (ClientService) Proxy.newProxyInstance(
                ClientService.class.getClassLoader(),
                new Class[]{ClientService.class},
                (proxy, method, args) -> {
                    if ("findClientById".equals(method.getName())) {
                        Client c = new Client((Integer) args[0], "client-" + args[0], "tok", "cn", "n", new Date());
                        return c;
                    }
                    return defaultProxyMethod(proxy, method, args, "ClientServiceStub");
                });
    }

    /**
     * 构造 SseEventBus stub，仅记录 publishAlertFired 调用以便测试断言。
     */
    private SseEventBus stubSseEventBus() {
        return (SseEventBus) Proxy.newProxyInstance(
                SseEventBus.class.getClassLoader(),
                new Class[]{SseEventBus.class},
                (proxy, method, args) -> {
                    if ("publishAlertFired".equals(method.getName())
                            && args.length == 1
                            && args[0] instanceof AlertHistoryVO vo) {
                        synchronized (publishedSseAlerts) {
                            publishedSseAlerts.add(vo);
                        }
                        return null;
                    }
                    return defaultProxyMethod(proxy, method, args, "SseEventBusStub");
                });
    }

    /**
     * 构造 AlertStructMapper stub，仅实现 toHistoryVO（依据 history 字段直接构造 VO）。
     */
    private AlertStructMapper stubAlertStructMapper() {
        return (AlertStructMapper) Proxy.newProxyInstance(
                AlertStructMapper.class.getClassLoader(),
                new Class[]{AlertStructMapper.class},
                (proxy, method, args) -> {
                    if ("toHistoryVO".equals(method.getName())
                            && args.length == 1
                            && args[0] instanceof AlertHistory h) {
                        AlertHistoryVO vo = new AlertHistoryVO();
                        vo.setId(h.getId());
                        vo.setRuleId(h.getRuleId());
                        vo.setClientId(h.getClientId());
                        vo.setFiredAt(h.getFiredAt());
                        vo.setStatus(h.getStatus());
                        vo.setLevel(h.getLevel());
                        vo.setCurrentValue(h.getCurrentValue());
                        vo.setMessage(h.getMessage());
                        return vo;
                    }
                    return defaultProxyMethod(proxy, method, args, "AlertStructMapperStub");
                });
    }

    /**
     * 默认 proxy 行为：toString/hashCode/equals 等基础方法。
     */
    private Object defaultProxyMethod(Object proxy, java.lang.reflect.Method method, Object[] args, String label) {
        if ("toString".equals(method.getName())) {
            return label;
        }
        if ("hashCode".equals(method.getName())) {
            return System.identityHashCode(proxy);
        }
        if ("equals".equals(method.getName())) {
            return proxy == args[0];
        }
        // 对未在测试中使用的方法静默返回 null/默认值，避免噪音
        Class<?> ret = method.getReturnType();
        if (ret == boolean.class) return Boolean.FALSE;
        if (ret == int.class) return 0;
        if (ret == long.class) return 0L;
        if (ret == double.class) return 0.0;
        return null;
    }
}
