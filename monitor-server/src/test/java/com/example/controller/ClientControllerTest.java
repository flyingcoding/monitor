package com.example.controller;

import com.example.entity.dto.Client;
import com.example.service.ClientService;
import com.example.utils.Const;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Date;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ClientControllerTest {

    private MockMvc mockMvc;
    private final AtomicBoolean registerClientResult = new AtomicBoolean(false);
    private final AtomicInteger runtimeUpdateCount = new AtomicInteger(0);

    /**
     * 构建独立Controller测试上下文，使用轻量动态代理桩替代Mockito，避免依赖JVM attach能力。
     */
    @BeforeEach
    void setUp() {
        ClientController controller = new ClientController();
        ClientService clientService = (ClientService) Proxy.newProxyInstance(
                ClientService.class.getClassLoader(),
                new Class[]{ClientService.class},
                (proxy, method, args) -> {
                    if ("registerClient".equals(method.getName())) {
                        return registerClientResult.get();
                    }
                    if ("updateRuntimeDetail".equals(method.getName())) {
                        runtimeUpdateCount.incrementAndGet();
                        return null;
                    }
                    if ("toString".equals(method.getName())) {
                        return "ClientServiceTestStub";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    throw new UnsupportedOperationException("测试桩未实现方法: " + method.getName());
                }
        );
        ReflectionTestUtils.setField(controller, "clientService", clientService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /**
     * 验证客户端在有效Token下注册接口返回成功响应。
     */
    @Test
    void registerClientWithValidTokenShouldReturnSuccess() throws Exception {
        registerClientResult.set(true);
        mockMvc.perform(get("/monitor/register").header("Authorization", "valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 验证客户端在无效Token下注册接口返回失败响应。
     */
    @Test
    void registerClientWithInvalidTokenShouldReturnFailure() throws Exception {
        registerClientResult.set(false);
        mockMvc.perform(get("/monitor/register").header("Authorization", "invalid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    /**
     * 验证批量运行时上报接口返回成功，并按批量大小调用服务层处理逻辑。
     */
    @Test
    void runtimeBatchShouldReturnSuccessAndInvokeServicePerItem() throws Exception {
        runtimeUpdateCount.set(0);
        Client client = new Client(1, "n1", "t1", "cn", "node-1", new Date());
        String payload = """
                [
                  {
                    "timestamp": 1700000000000,
                    "cpuUsage": 0.1,
                    "memoryUsage": 0.2,
                    "diskUsage": 0.3,
                    "networkUpload": 1.0,
                    "networkDownload": 2.0,
                    "diskRead": 3.0,
                    "diskWrite": 4.0
                  },
                  {
                    "timestamp": 1700000001000,
                    "cpuUsage": 0.4,
                    "memoryUsage": 0.5,
                    "diskUsage": 0.6,
                    "networkUpload": 5.0,
                    "networkDownload": 6.0,
                    "diskRead": 7.0,
                    "diskWrite": 8.0
                  }
                ]
                """;

        mockMvc.perform(post("/monitor/runtime/batch")
                        .contentType("application/json")
                        .requestAttr(Const.ATTR_CLIENT, client)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        org.junit.jupiter.api.Assertions.assertEquals(2, runtimeUpdateCount.get());
    }

    /**
     * 验证批量运行时上报接口会拒绝包含 null 元素的请求体，避免服务层空指针。
     */
    @Test
    void runtimeBatchShouldRejectNullItem() throws Exception {
        runtimeUpdateCount.set(0);
        Client client = new Client(1, "n1", "t1", "cn", "node-1", new Date());

        mockMvc.perform(post("/monitor/runtime/batch")
                        .contentType("application/json")
                        .requestAttr(Const.ATTR_CLIENT, client)
                        .content("[null]"))
                .andExpect(status().isBadRequest());

        org.junit.jupiter.api.Assertions.assertEquals(0, runtimeUpdateCount.get());
    }

    /**
     * 验证批量上报在出现中途无效元素时会整体拒绝，且不会产生部分写入。
     */
    @Test
    void runtimeBatchShouldNotPartiallyPersistWhenContainsNullItem() throws Exception {
        runtimeUpdateCount.set(0);
        Client client = new Client(1, "n1", "t1", "cn", "node-1", new Date());
        String payload = """
                [
                  {
                    "timestamp": 1700000000000,
                    "cpuUsage": 0.1,
                    "memoryUsage": 0.2,
                    "diskUsage": 0.3,
                    "networkUpload": 1.0,
                    "networkDownload": 2.0,
                    "diskRead": 3.0,
                    "diskWrite": 4.0
                  },
                  null
                ]
                """;

        mockMvc.perform(post("/monitor/runtime/batch")
                        .contentType("application/json")
                        .requestAttr(Const.ATTR_CLIENT, client)
                        .content(payload))
                .andExpect(status().isBadRequest());

        org.junit.jupiter.api.Assertions.assertEquals(0, runtimeUpdateCount.get());
    }
}
