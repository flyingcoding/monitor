package com.example.controller;

import com.example.entity.alert.AlertEvent;
import com.example.entity.dto.NotificationChannel;
import com.example.service.PermissionService;
import com.example.service.impl.NotificationChannelServiceImpl;
import com.example.service.notification.NotificationChannelSender;
import com.example.utils.Const;
import com.example.utils.CryptoUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通知通道 Controller 单元测试。复用项目既有的 {@code MockMvcBuilders.standaloneSetup}
 * 与轻量自实现桩对象模式，避免 Mockito 在 macOS aarch64 + Java 21 上的 JVM attach 限制。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>创建时 _enc 字段加密入库</li>
 *   <li>更新时 "***" 占位符保留旧密文、新明文重新加密</li>
 *   <li>列表/详情响应 _enc 字段被遮罩</li>
 *   <li>删除前引用检查：被引用 → 409、未被引用 → 成功</li>
 *   <li>测试发送：sender 被调用、失败时返回 500</li>
 *   <li>非管理员访问 → 401 noPermission</li>
 * </ul>
 */
class NotificationChannelControllerTest {

    private static final String BASE64_KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private NotificationChannelController controller;
    private NotificationChannelServiceImpl service;
    private StubSender stubSender;
    private CryptoUtils cryptoUtils;
    private Map<Long, NotificationChannel> store;
    private long nextId;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        cryptoUtils = new CryptoUtils(BASE64_KEY);
        store = new LinkedHashMap<>();
        nextId = 1L;

        service = new NotificationChannelServiceImpl() {
            @Override
            public boolean save(NotificationChannel entity) {
                if (entity.getId() == null) {
                    entity.setId(nextId++);
                }
                store.put(entity.getId(), entity);
                return true;
            }

            @Override
            public boolean updateById(NotificationChannel entity) {
                store.put(entity.getId(), entity);
                return true;
            }

            @Override
            public NotificationChannel getById(java.io.Serializable id) {
                return store.get(((Number) id).longValue());
            }

            @Override
            public boolean removeById(java.io.Serializable id) {
                return store.remove(((Number) id).longValue()) != null;
            }

            @Override
            public List<NotificationChannel> list() {
                return new ArrayList<>(store.values());
            }
        };
        ReflectionTestUtils.setField(service, "cryptoUtils", cryptoUtils);

        // isReferenced 的默认行为由测试覆写（默认未被引用）
        ReflectionTestUtils.setField(service, "alertRuleMapper", null);

        stubSender = new StubSender();

        controller = new NotificationChannelController();
        ReflectionTestUtils.setField(controller, "notificationChannelService", service);
        ReflectionTestUtils.setField(controller, "permissionService", new AdminAlwaysPermissionService());
        ReflectionTestUtils.setField(controller, "notificationChannelSenders", List.of(stubSender));

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /**
     * 创建通道时，config 中 _enc 字段必须被加密入库；普通字段保持明文。
     */
    @Test
    void createShouldEncryptEncSuffixedFields() throws Exception {
        String payload = """
                {
                  "name": "test-webhook",
                  "type": "webhook",
                  "enabled": true,
                  "config": {
                    "url": "https://hook.example.com",
                    "token_enc": "my-secret-token"
                  }
                }
                """;

        mockMvc.perform(post("/api/notification/channel")
                        .contentType("application/json")
                        .requestAttr(Const.ATTR_USER_ROLE, "admin")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.config.token_enc").value("***"))
                .andExpect(jsonPath("$.data.config.url").value("https://hook.example.com"));

        assertEquals(1, store.size());
        NotificationChannel stored = store.values().iterator().next();
        Object encStored = stored.getConfig().get("token_enc");
        assertNotNull(encStored);
        assertTrue(encStored.toString().startsWith("ENC:"), "_enc 字段应被加密为 ENC: 前缀的密文");
        assertEquals("my-secret-token", cryptoUtils.decrypt(encStored.toString()));
        // 非敏感字段保持原值
        assertEquals("https://hook.example.com", stored.getConfig().get("url"));
    }

    /**
     * 第四轮审查 P2 回归：_enc 字段值为 Map / 对象时（如 webhook 的 headers_enc），
     * service 层应先 JSON 序列化为字符串再加密，避免内部 token 明文落库。
     */
    @Test
    void createShouldSerializeAndEncryptObjectValuedEncField() throws Exception {
        String payload = """
                {
                  "name": "webhook-with-headers",
                  "type": "webhook",
                  "enabled": true,
                  "config": {
                    "url": "https://hook.example.com",
                    "headers_enc": {
                      "X-Token": "sk-secret",
                      "X-Source": "monitor"
                    }
                  }
                }
                """;

        mockMvc.perform(post("/api/notification/channel")
                        .contentType("application/json")
                        .requestAttr(Const.ATTR_USER_ROLE, "admin")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.config.headers_enc").value("***"));

        assertEquals(1, store.size());
        NotificationChannel stored = store.values().iterator().next();
        Object encStored = stored.getConfig().get("headers_enc");
        assertNotNull(encStored);
        assertTrue(encStored instanceof String, "Object 值序列化后应以 String 形式存入");
        String enc = encStored.toString();
        assertTrue(enc.startsWith("ENC:"), "Object 值应被加密为 ENC: 前缀的密文");
        // 解密后应能反序列化为原 Map 结构
        String decrypted = cryptoUtils.decrypt(enc);
        com.fasterxml.jackson.databind.JsonNode parsed = new com.fasterxml.jackson.databind.ObjectMapper().readTree(decrypted);
        assertEquals("sk-secret", parsed.get("X-Token").asText());
        assertEquals("monitor", parsed.get("X-Source").asText());
    }

    /**
     * 更新通道时，传入 "***" 占位的 _enc 字段应保留旧密文，传入新明文则重新加密。
     */
    @Test
    void updateShouldPreserveMaskedEncAndEncryptNewValue() throws Exception {
        // 先建一个含密文的通道
        NotificationChannel existing = new NotificationChannel();
        existing.setId(1L);
        existing.setName("origin");
        existing.setType("webhook");
        existing.setEnabled(true);
        Map<String, Object> initialConfig = new LinkedHashMap<>();
        initialConfig.put("url", "https://old.example.com");
        initialConfig.put("token_enc", cryptoUtils.encrypt("old-secret"));
        existing.setConfig(initialConfig);
        existing.setCreatedAt(new Date());
        store.put(1L, existing);
        nextId = 2L;

        // 情形 1：前端回传 "***"（未修改），应保留旧密文
        String preservePayload = """
                {
                  "name": "renamed",
                  "type": "webhook",
                  "enabled": true,
                  "config": {
                    "url": "https://new.example.com",
                    "token_enc": "***"
                  }
                }
                """;
        mockMvc.perform(put("/api/notification/channel/1")
                        .contentType("application/json")
                        .requestAttr(Const.ATTR_USER_ROLE, "admin")
                        .content(preservePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        NotificationChannel afterMask = store.get(1L);
        assertEquals("renamed", afterMask.getName());
        assertEquals("https://new.example.com", afterMask.getConfig().get("url"));
        assertEquals("old-secret", cryptoUtils.decrypt(afterMask.getConfig().get("token_enc").toString()),
                "*** 占位时应保留旧密文");

        // 情形 2：前端回传新明文，应重新加密
        String rotatePayload = """
                {
                  "name": "renamed",
                  "type": "webhook",
                  "enabled": true,
                  "config": {
                    "url": "https://new.example.com",
                    "token_enc": "new-secret"
                  }
                }
                """;
        mockMvc.perform(put("/api/notification/channel/1")
                        .contentType("application/json")
                        .requestAttr(Const.ATTR_USER_ROLE, "admin")
                        .content(rotatePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        NotificationChannel afterRotate = store.get(1L);
        Object newEnc = afterRotate.getConfig().get("token_enc");
        assertTrue(newEnc.toString().startsWith("ENC:"));
        assertEquals("new-secret", cryptoUtils.decrypt(newEnc.toString()), "新明文应被加密");
    }

    /**
     * 列表 / 详情响应必须遮罩 _enc 字段，避免敏感凭证泄漏。
     */
    @Test
    void listAndDetailShouldMaskEncFields() throws Exception {
        NotificationChannel existing = new NotificationChannel();
        existing.setId(1L);
        existing.setName("dingtalk-bot");
        existing.setType("dingtalk");
        existing.setEnabled(true);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("webhook_url_enc", cryptoUtils.encrypt("https://oapi.dingtalk.com/robot/send?access_token=abc"));
        cfg.put("secret_enc", cryptoUtils.encrypt("SEC123"));
        cfg.put("at_mobiles", "13800000000");
        existing.setConfig(cfg);
        existing.setCreatedAt(new Date());
        store.put(1L, existing);

        mockMvc.perform(get("/api/notification/channel")
                        .requestAttr(Const.ATTR_USER_ROLE, "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].config.webhook_url_enc").value("***"))
                .andExpect(jsonPath("$.data[0].config.secret_enc").value("***"))
                .andExpect(jsonPath("$.data[0].config.at_mobiles").value("13800000000"));

        mockMvc.perform(get("/api/notification/channel/1")
                        .requestAttr(Const.ATTR_USER_ROLE, "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.config.webhook_url_enc").value("***"))
                .andExpect(jsonPath("$.data.config.secret_enc").value("***"))
                .andExpect(jsonPath("$.data.config.at_mobiles").value("13800000000"));
    }

    /**
     * 删除被告警规则引用的通道时，应返回 409，且不删除记录。
     */
    @Test
    void deleteShouldRejectReferencedChannel() throws Exception {
        NotificationChannel existing = new NotificationChannel();
        existing.setId(1L);
        existing.setName("locked");
        existing.setType("webhook");
        existing.setEnabled(true);
        existing.setConfig(new LinkedHashMap<>());
        store.put(1L, existing);

        AtomicBoolean referenced = new AtomicBoolean(true);
        // 覆写 isReferenced：第一次返回 true，第二次返回 false
        NotificationChannelControllerTest.this.overrideIsReferenced(controller, referenced);

        mockMvc.perform(delete("/api/notification/channel/1")
                        .requestAttr(Const.ATTR_USER_ROLE, "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409));

        assertTrue(store.containsKey(1L), "被引用时不应删除");

        // 第二次：解除引用后再删除
        referenced.set(false);
        mockMvc.perform(delete("/api/notification/channel/1")
                        .requestAttr(Const.ATTR_USER_ROLE, "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertNull(store.get(1L), "未被引用时应删除成功");
    }

    /**
     * 测试发送接口应同步调用对应类型的 sender，并把 _enc 字段解密后传入。
     */
    @Test
    void testEndpointShouldInvokeSenderWithDecryptedConfig() throws Exception {
        NotificationChannel existing = new NotificationChannel();
        existing.setId(1L);
        existing.setName("stub-channel");
        existing.setType("webhook");
        existing.setEnabled(true);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("url", "https://hook.example.com");
        cfg.put("token_enc", cryptoUtils.encrypt("plain-token"));
        existing.setConfig(cfg);
        store.put(1L, existing);

        mockMvc.perform(post("/api/notification/channel/1/test")
                        .requestAttr(Const.ATTR_USER_ROLE, "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("测试通知已发送"));

        assertEquals(1, stubSender.callCount.get(), "sender 应被同步调用一次");
        Map<String, Object> receivedConfig = stubSender.lastConfig.get();
        assertNotNull(receivedConfig);
        assertEquals("plain-token", receivedConfig.get("token_enc"),
                "sender 接收的 _enc 字段应为解密后的明文");
        assertEquals("https://hook.example.com", receivedConfig.get("url"));

        AlertEvent event = stubSender.lastEvent.get();
        assertNotNull(event);
        assertEquals("test-client", event.getClientName());
        assertEquals("cpu", event.getMetric());
        assertEquals("这是一条测试通知", event.getMessage());
    }

    /**
     * sender 抛异常时，测试发送应返回 500 + 失败原因；不应抛出 5xx HTTP。
     */
    @Test
    void testEndpointShouldReturnFailureWhenSenderThrows() throws Exception {
        NotificationChannel existing = new NotificationChannel();
        existing.setId(1L);
        existing.setName("bad-channel");
        existing.setType("webhook");
        existing.setEnabled(true);
        existing.setConfig(new LinkedHashMap<>());
        store.put(1L, existing);

        stubSender.shouldThrow.set(true);
        stubSender.errorMessage = "外部服务超时";

        mockMvc.perform(post("/api/notification/channel/1/test")
                        .requestAttr(Const.ATTR_USER_ROLE, "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("发送失败: 外部服务超时"));
    }

    /**
     * 未找到匹配 type 的 sender 时返回 400，避免静默吞掉错误。
     */
    @Test
    void testEndpointShouldReturnBadRequestWhenSenderMissing() throws Exception {
        NotificationChannel existing = new NotificationChannel();
        existing.setId(1L);
        existing.setName("unknown-type");
        existing.setType("telegram"); // stubSender 只支持 webhook
        existing.setEnabled(true);
        existing.setConfig(new LinkedHashMap<>());
        store.put(1L, existing);

        mockMvc.perform(post("/api/notification/channel/1/test")
                        .requestAttr(Const.ATTR_USER_ROLE, "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    /**
     * 非管理员访问任何端点应返回 noPermission。
     */
    @Test
    void nonAdminShouldGetNoPermission() throws Exception {
        ReflectionTestUtils.setField(controller, "permissionService", new NeverAdminPermissionService());

        mockMvc.perform(get("/api/notification/channel")
                        .requestAttr(Const.ATTR_USER_ROLE, "user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    /**
     * 用反射覆盖控制器持有的 service 实例的 isReferenced 行为，避免引入 Mockito。
     *
     * @param controller 控制器
     * @param flag 引用状态切换开关
     */
    private void overrideIsReferenced(NotificationChannelController controller, AtomicBoolean flag) {
        NotificationChannelServiceImpl original = (NotificationChannelServiceImpl)
                ReflectionTestUtils.getField(controller, "notificationChannelService");
        NotificationChannelServiceImpl override = new NotificationChannelServiceImpl() {
            @Override
            public boolean isReferenced(Long channelId) {
                return flag.get();
            }

            @Override
            public boolean save(NotificationChannel entity) {
                return original.save(entity);
            }

            @Override
            public boolean updateById(NotificationChannel entity) {
                return original.updateById(entity);
            }

            @Override
            public NotificationChannel getById(java.io.Serializable id) {
                return original.getById(id);
            }

            @Override
            public boolean removeById(java.io.Serializable id) {
                return original.removeById(id);
            }

            @Override
            public List<NotificationChannel> list() {
                return original.list();
            }

            @Override
            public void encryptSensitive(Map<String, Object> config) {
                original.encryptSensitive(config);
            }

            @Override
            public void preserveExistingEnc(Map<String, Object> newConfig, Map<String, Object> oldConfig) {
                original.preserveExistingEnc(newConfig, oldConfig);
            }

            @Override
            public Map<String, Object> decryptSensitive(Map<String, Object> config) {
                return original.decryptSensitive(config);
            }
        };
        ReflectionTestUtils.setField(override, "cryptoUtils", cryptoUtils);
        ReflectionTestUtils.setField(controller, "notificationChannelService", override);
    }

    /**
     * 测试桩：捕获最后一次 send 调用的 event/config，用于断言。
     */
    private static class StubSender implements NotificationChannelSender {
        final AtomicReference<AlertEvent> lastEvent = new AtomicReference<>();
        final AtomicReference<Map<String, Object>> lastConfig = new AtomicReference<>();
        final java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();
        final AtomicBoolean shouldThrow = new AtomicBoolean(false);
        String errorMessage = "";

        @Override
        public String type() {
            return "webhook";
        }

        @Override
        public void send(AlertEvent event, Map<String, Object> config) throws Exception {
            callCount.incrementAndGet();
            lastEvent.set(event);
            lastConfig.set(config);
            if (shouldThrow.get()) {
                throw new RuntimeException(errorMessage);
            }
        }
    }

    /**
     * 测试用 PermissionService：始终判定为管理员。
     */
    private static class AdminAlwaysPermissionService extends PermissionService {
        @Override
        public boolean isAdmin(String role) {
            return true;
        }
    }

    /**
     * 测试用 PermissionService：始终判定为非管理员。
     */
    private static class NeverAdminPermissionService extends PermissionService {
        @Override
        public boolean isAdmin(String role) {
            return false;
        }
    }

    /**
     * 防止 IDE 未使用警告：未来若需要反射调用私有方法可使用此辅助。
     */
    @SuppressWarnings("unused")
    private static Object invokePrivate(Object target, String name, Class<?>[] types, Object[] args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }
}
