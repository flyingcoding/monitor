package com.example.controller;

import com.example.service.ClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ClientControllerTest {

    private MockMvc mockMvc;
    private final AtomicBoolean registerClientResult = new AtomicBoolean(false);

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
}
