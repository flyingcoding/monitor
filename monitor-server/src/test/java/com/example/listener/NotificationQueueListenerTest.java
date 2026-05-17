package com.example.listener;

import com.example.entity.alert.AlertEvent;
import com.example.entity.dto.NotificationChannel;
import com.example.mapper.NotificationChannelMapper;
import com.example.service.notification.NotificationChannelSender;
import com.example.utils.CryptoUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NotificationQueueListener 单元测试。
 * <p>
 * 项目惯例：不引 Mockito，使用 JDK 动态代理 + 匿名/具名子类作桩（参考 ClientControllerTest /
 * AlertEvaluatorImplTest）。
 * <p>
 * 覆盖第四轮审查 P2 修复：区分 attempted 与 skipped，避免「禁用 + 失败」组合错误 ACK 导致告警丢失。
 */
class NotificationQueueListenerTest {

    private static final String BASE64_KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private NotificationQueueListener listener;
    private Map<Long, NotificationChannel> store;
    private StubSender successSender;
    private StubSender failingSender;

    @BeforeEach
    void setUp() {
        store = new LinkedHashMap<>();
        listener = new NotificationQueueListener();

        successSender = new StubSender("ok", false);
        failingSender = new StubSender("fail", true);

        NotificationChannelMapper mapper = (NotificationChannelMapper) Proxy.newProxyInstance(
                NotificationChannelMapper.class.getClassLoader(),
                new Class[]{NotificationChannelMapper.class},
                (proxy, method, args) -> {
                    if ("selectById".equals(method.getName()) && args.length == 1) {
                        Object arg = args[0];
                        if (arg instanceof Number n) {
                            return store.get(n.longValue());
                        }
                    }
                    return defaultProxyMethod(proxy, method, args, "NotificationChannelMapperStub");
                });

        ReflectionTestUtils.setField(listener, "senders", List.of(successSender, failingSender));
        ReflectionTestUtils.setField(listener, "notificationChannelMapper", mapper);
        ReflectionTestUtils.setField(listener, "cryptoUtils", new CryptoUtils(BASE64_KEY));
        listener.init();
    }

    /**
     * 当唯一一个被尝试的通道发送失败（其它通道全被跳过：禁用/不存在/类型未注册）时，
     * 应抛 RuntimeException 触发 DLX，避免因「无成功且无尝试匹配 total」的错误 ACK 丢告警。
     */
    @Test
    void should_throw_when_all_attempted_channels_failed() {
        // 通道 1：已禁用 → skipped
        NotificationChannel disabled = new NotificationChannel();
        disabled.setId(1L);
        disabled.setName("disabled-mail");
        disabled.setType("ok");
        disabled.setEnabled(false);
        disabled.setConfig(new HashMap<>());
        store.put(1L, disabled);

        // 通道 2：启用且类型为 fail → attempted 1 + failed 1
        NotificationChannel failing = new NotificationChannel();
        failing.setId(2L);
        failing.setName("failing-webhook");
        failing.setType("fail");
        failing.setEnabled(true);
        failing.setConfig(new HashMap<>());
        store.put(2L, failing);

        AlertEvent event = buildEvent(List.of(1L, 2L));

        RuntimeException ex = Assertions.assertThrows(RuntimeException.class,
                () -> listener.handleAlertEvent(event),
                "尝试过的通道全失败时必须抛异常进入 DLX");
        Assertions.assertTrue(ex.getMessage().contains("DLX"),
                "异常消息应说明触发 DLX，实际: " + ex.getMessage());
        Assertions.assertEquals(0, successSender.callCount.get(), "ok 类型通道未被引用，不应被调用");
        Assertions.assertEquals(1, failingSender.callCount.get(), "fail 类型通道应被调用一次");
    }

    /**
     * 当所有通道都被跳过（用户主动禁用所有引用通道）时，不应抛异常 —— 视为合法状态，
     * 告警仅落库不发外通知。
     */
    @Test
    void should_ack_when_all_skipped() {
        NotificationChannel disabled1 = new NotificationChannel();
        disabled1.setId(1L);
        disabled1.setName("disabled-a");
        disabled1.setType("ok");
        disabled1.setEnabled(false);
        disabled1.setConfig(new HashMap<>());
        store.put(1L, disabled1);

        NotificationChannel disabled2 = new NotificationChannel();
        disabled2.setId(2L);
        disabled2.setName("disabled-b");
        disabled2.setType("ok");
        disabled2.setEnabled(false);
        disabled2.setConfig(new HashMap<>());
        store.put(2L, disabled2);

        AlertEvent event = buildEvent(List.of(1L, 2L));

        Assertions.assertDoesNotThrow(() -> listener.handleAlertEvent(event),
                "全部通道被禁用是合法状态，应 ACK 而非进 DLX");
        Assertions.assertEquals(0, successSender.callCount.get());
        Assertions.assertEquals(0, failingSender.callCount.get());
    }

    /**
     * 至少一个通道发送成功（即便有其他通道失败）应 ACK，避免重复重试已成功的通道。
     */
    @Test
    void should_ack_when_at_least_one_succeeded() {
        NotificationChannel success = new NotificationChannel();
        success.setId(1L);
        success.setName("success-channel");
        success.setType("ok");
        success.setEnabled(true);
        success.setConfig(new HashMap<>());
        store.put(1L, success);

        NotificationChannel failing = new NotificationChannel();
        failing.setId(2L);
        failing.setName("failing-channel");
        failing.setType("fail");
        failing.setEnabled(true);
        failing.setConfig(new HashMap<>());
        store.put(2L, failing);

        AlertEvent event = buildEvent(List.of(1L, 2L));

        Assertions.assertDoesNotThrow(() -> listener.handleAlertEvent(event),
                "至少一个通道发送成功时应 ACK");
        Assertions.assertEquals(1, successSender.callCount.get());
        Assertions.assertEquals(1, failingSender.callCount.get());
    }

    /**
     * 不存在的通道（channelId 在 DB 中查不到）也是 skipped 而非 failed —— 若所有通道都不存在，
     * 同样应 ACK，避免无效 channel_ids 导致死循环重试。
     */
    @Test
    void should_ack_when_channel_not_found() {
        // 不向 store 放任何通道
        AlertEvent event = buildEvent(List.of(99L, 100L));

        Assertions.assertDoesNotThrow(() -> listener.handleAlertEvent(event),
                "通道全部不存在时应 ACK，避免对无效配置无限重试");
        Assertions.assertEquals(0, successSender.callCount.get());
        Assertions.assertEquals(0, failingSender.callCount.get());
    }

    private static AlertEvent buildEvent(List<Long> channelIds) {
        return AlertEvent.builder()
                .ruleId(1L)
                .historyId(1L)
                .clientId(10)
                .clientName("test-client")
                .metric("cpu")
                .operator("gt")
                .threshold(80.0)
                .currentValue(95.0)
                .level("warning")
                .message("test")
                .firedAt(LocalDateTime.now())
                .channelIds(channelIds)
                .build();
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
        Class<?> ret = method.getReturnType();
        if (ret == boolean.class) return Boolean.FALSE;
        if (ret == int.class) return 0;
        if (ret == long.class) return 0L;
        if (ret == double.class) return 0.0;
        return null;
    }

    /**
     * 测试桩 sender：可配置类型标识与是否抛异常，记录调用次数。
     */
    private static class StubSender implements NotificationChannelSender {
        private final String type;
        private final boolean shouldThrow;
        final AtomicInteger callCount = new AtomicInteger();

        StubSender(String type, boolean shouldThrow) {
            this.type = type;
            this.shouldThrow = shouldThrow;
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public void send(AlertEvent event, Map<String, Object> config) {
            callCount.incrementAndGet();
            if (shouldThrow) {
                throw new RuntimeException("stub-send-failure");
            }
        }
    }
}
