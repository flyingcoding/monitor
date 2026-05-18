package com.example.service;

import com.example.entity.alert.AlertEvent;
import com.example.entity.dto.ProbeTask;
import com.example.service.impl.ProbeScheduler;
import com.example.service.impl.ProbeServiceImpl;
import com.example.service.impl.probe.HttpProbeExecutor;
import com.example.service.impl.probe.IcmpProbeExecutor;
import com.example.service.impl.probe.ProbeResult;
import com.example.service.impl.probe.TcpProbeExecutor;
import com.example.utils.Const;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link ProbeScheduler} 单元测试。
 *
 * <p>主要覆盖：连续失败累积 → 告警；单次失败不告警；SSL 即将过期独立分支；成功重置计数；
 * 投递的 {@link AlertEvent} 字段（metric / operator / channelIds / level）符合 PRD 约定。
 */
class ProbeSchedulerTest {

    private ProbeScheduler scheduler;
    private final ConcurrentLinkedQueue<AlertEvent> publishedEvents = new ConcurrentLinkedQueue<>();
    private final AtomicReference<ProbeResult> nextHttpResult = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        scheduler = new ProbeScheduler();
        publishedEvents.clear();
        nextHttpResult.set(ProbeResult.builder().success(true).latencyMs(10).build());

        // ProbeServiceImpl stub：resolveHeaders / resolveBasicAuthPassword / resolveChannelIds / saveHistory
        ProbeServiceImpl probeService = new ProbeServiceImpl() {
            @Override
            public Map<String, String> resolveHeaders(ProbeTask task) {
                return Collections.emptyMap();
            }

            @Override
            public String resolveBasicAuthPassword(ProbeTask task) {
                return null;
            }

            @Override
            public List<Long> resolveChannelIds(ProbeTask task) {
                return List.of(101L, 202L);
            }

            @Override
            public boolean saveHistory(com.example.entity.dto.ProbeHistory history) {
                return true;
            }
        };

        // HttpProbeExecutor stub：返回 nextHttpResult
        HttpProbeExecutor httpExec = new HttpProbeExecutor() {
            @Override
            public ProbeResult execute(ProbeTask t, Map<String, String> hdrs, String pwd) {
                ProbeResult r = nextHttpResult.get();
                return r == null
                        ? ProbeResult.builder().success(false).errorMessage("no result").build()
                        : r;
            }
        };
        TcpProbeExecutor tcpExec = new TcpProbeExecutor() {
            @Override
            public ProbeResult execute(ProbeTask t, Map<String, String> hdrs, String pwd) {
                return ProbeResult.builder().success(true).latencyMs(5).build();
            }
        };
        IcmpProbeExecutor icmpExec = new IcmpProbeExecutor() {
            @Override
            public ProbeResult execute(ProbeTask t, Map<String, String> hdrs, String pwd) {
                return ProbeResult.builder().success(true).latencyMs(2).build();
            }
        };

        // AmqpTemplate stub：捕获投递的 AlertEvent
        AmqpTemplate rabbit = (AmqpTemplate) Proxy.newProxyInstance(
                AmqpTemplate.class.getClassLoader(),
                new Class[]{AmqpTemplate.class},
                (proxy, method, args) -> {
                    if ("convertAndSend".equals(method.getName())
                            && args.length >= 2
                            && Const.MQ_NOTIFICATION.equals(args[0])
                            && args[1] instanceof AlertEvent ev) {
                        publishedEvents.add(ev);
                    }
                    return null;
                });

        ReflectionTestUtils.setField(scheduler, "probeService", probeService);
        ReflectionTestUtils.setField(scheduler, "httpProbeExecutor", httpExec);
        ReflectionTestUtils.setField(scheduler, "tcpProbeExecutor", tcpExec);
        ReflectionTestUtils.setField(scheduler, "icmpProbeExecutor", icmpExec);
        ReflectionTestUtils.setField(scheduler, "rabbitTemplate", rabbit);
    }

    /**
     * 单次失败不应触发告警（threshold=2）。
     */
    @Test
    void singleFailureShouldNotPublishAlert() {
        ProbeTask task = task("once", 2);
        ProbeResult fail = ProbeResult.builder().success(false).errorMessage("conn refused").build();
        scheduler.evaluateAndAlert(task, fail);
        Assertions.assertEquals(0, publishedEvents.size());
        Assertions.assertEquals(1, scheduler.failureCounterForTest(task.getId()));
    }

    /**
     * 连续 2 次失败应触发一次告警；之后计数清零。
     */
    @Test
    void reachingThresholdShouldPublishAlert() {
        ProbeTask task = task("twice", 2);
        ProbeResult fail1 = ProbeResult.builder().success(false).errorMessage("timeout").build();
        ProbeResult fail2 = ProbeResult.builder().success(false).errorMessage("conn refused").build();
        scheduler.evaluateAndAlert(task, fail1);
        scheduler.evaluateAndAlert(task, fail2);

        Assertions.assertEquals(1, publishedEvents.size(), "连续 2 次失败应投一次告警");
        AlertEvent event = publishedEvents.peek();
        Assertions.assertEquals("probe", event.getMetric());
        Assertions.assertEquals("fail", event.getOperator());
        Assertions.assertNull(event.getRuleId());
        Assertions.assertNull(event.getClientId());
        Assertions.assertEquals("twice", event.getClientName());
        Assertions.assertEquals(List.of(101L, 202L), event.getChannelIds());
        Assertions.assertEquals(2.0, event.getCurrentValue(), 0.001);
        Assertions.assertEquals(2.0, event.getThreshold(), 0.001);

        // 计数器清零：再失败一次仍不应触发新告警
        scheduler.evaluateAndAlert(task,
                ProbeResult.builder().success(false).errorMessage("again").build());
        Assertions.assertEquals(1, publishedEvents.size());
    }

    /**
     * 探测成功应清空连续失败计数。
     */
    @Test
    void successShouldResetFailureCounter() {
        ProbeTask task = task("recovery", 3);
        scheduler.evaluateAndAlert(task,
                ProbeResult.builder().success(false).errorMessage("e").build());
        Assertions.assertEquals(1, scheduler.failureCounterForTest(task.getId()));

        scheduler.evaluateAndAlert(task,
                ProbeResult.builder().success(true).latencyMs(100).build());
        Assertions.assertEquals(0, scheduler.failureCounterForTest(task.getId()));
    }

    /**
     * SSL 即将过期：单次结果带 sslExpiringSoon=true 即触发告警；同一任务不重复投递。
     */
    @Test
    void sslExpiringSoonShouldPublishOnce() {
        ProbeTask task = task("ssl-expire", 5);
        task.setSslWarnDays(30);
        ProbeResult expiring = ProbeResult.builder()
                .success(true)
                .latencyMs(20)
                .statusCode(200)
                .sslDaysRemaining(7)
                .sslExpiringSoon(true)
                .build();
        scheduler.evaluateAndAlert(task, expiring);
        Assertions.assertEquals(1, publishedEvents.size(), "SSL 即将过期应触发告警");
        AlertEvent event = publishedEvents.peek();
        Assertions.assertEquals("probe", event.getMetric());
        Assertions.assertEquals("fail", event.getOperator());
        Assertions.assertEquals(7.0, event.getCurrentValue(), 0.001);
        Assertions.assertEquals(30.0, event.getThreshold(), 0.001);

        // 再来一次相同的"即将过期"结果，不应再投
        scheduler.evaluateAndAlert(task, expiring);
        Assertions.assertEquals(1, publishedEvents.size());
        Assertions.assertTrue(scheduler.sslAlertedForTest(task.getId()));
    }

    /**
     * SSL 状态恢复（剩余天 > 阈值且 ssExpiringSoon=false）应清除"已告警"标记，允许下次过期再次告警。
     */
    @Test
    void sslRecoveryShouldAllowReAlertingNextExpiry() {
        ProbeTask task = task("ssl-cycle", 2);
        task.setSslWarnDays(30);
        // 首次过期 → 告警
        scheduler.evaluateAndAlert(task, ProbeResult.builder()
                .success(true).sslDaysRemaining(5).sslExpiringSoon(true).build());
        Assertions.assertEquals(1, publishedEvents.size());

        // 证书续期 → 重置
        scheduler.evaluateAndAlert(task, ProbeResult.builder()
                .success(true).sslDaysRemaining(180).sslExpiringSoon(false).build());
        Assertions.assertFalse(scheduler.sslAlertedForTest(task.getId()));

        // 再次过期 → 再次告警
        scheduler.evaluateAndAlert(task, ProbeResult.builder()
                .success(true).sslDaysRemaining(2).sslExpiringSoon(true).build());
        Assertions.assertEquals(2, publishedEvents.size());
    }

    /**
     * runSingle 应使用对应 type 的 executor 并写历史；HTTP 类型应路由到 httpExec stub。
     */
    @Test
    void runSingleShouldRouteToHttpExecutor() {
        ProbeTask task = task("via-runSingle", 5);
        nextHttpResult.set(ProbeResult.builder().success(true).latencyMs(42).statusCode(200).build());
        scheduler.runSingle(task);
        // 成功 → 不投告警
        Assertions.assertEquals(0, publishedEvents.size());
        Assertions.assertEquals(0, scheduler.failureCounterForTest(task.getId()));
    }

    /**
     * runSingle 对未知 type 不抛异常（仅日志），不写历史也不告警。
     */
    @Test
    void runSingleWithUnknownTypeShouldNoop() {
        ProbeTask task = new ProbeTask();
        task.setId(99L);
        task.setName("unknown");
        task.setType("smtp");
        task.setIntervalSec(60);
        task.setTimeoutSec(10);
        task.setConsecutiveFailuresThreshold(2);
        task.setEnabled(Boolean.TRUE);
        Assertions.assertDoesNotThrow(() -> scheduler.runSingle(task));
        Assertions.assertEquals(0, publishedEvents.size());
    }

    /**
     * 阈值 = 1：单次失败也应触发。
     */
    @Test
    void thresholdOneShouldPublishOnFirstFailure() {
        ProbeTask task = task("strict", 1);
        scheduler.evaluateAndAlert(task,
                ProbeResult.builder().success(false).errorMessage("immediate fail").build());
        Assertions.assertEquals(1, publishedEvents.size());
    }

    /**
     * clearStateForTest 应清空 internal state。
     */
    @Test
    void clearStateShouldResetEverything() {
        ProbeTask task = task("clear", 5);
        // 第一次失败 → failureCounter=1
        scheduler.evaluateAndAlert(task, ProbeResult.builder().success(false).build());
        Assertions.assertEquals(1, scheduler.failureCounterForTest(task.getId()));
        // 触发 SSL 即将过期分支（success=true 会重置 failureCounter，但 sslAlerted 仍记录）
        scheduler.evaluateAndAlert(task,
                ProbeResult.builder().success(true).sslDaysRemaining(5).sslExpiringSoon(true).build());
        Assertions.assertTrue(scheduler.sslAlertedForTest(task.getId()));

        scheduler.clearStateForTest();
        Assertions.assertEquals(0, scheduler.failureCounterForTest(task.getId()));
        Assertions.assertFalse(scheduler.sslAlertedForTest(task.getId()));
    }

    private ProbeTask task(String name, int threshold) {
        ProbeTask t = new ProbeTask();
        t.setId(System.identityHashCode(name) & 0xffffL);
        t.setName(name);
        t.setType("http");
        t.setTarget("https://example.com/health");
        t.setIntervalSec(60);
        t.setTimeoutSec(10);
        t.setConsecutiveFailuresThreshold(threshold);
        t.setEnabled(Boolean.TRUE);
        return t;
    }
}
