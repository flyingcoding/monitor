package com.example.service;

import com.example.entity.dto.Client;
import com.example.entity.dto.ClientSsh;
import com.example.entity.vo.request.SshConnectVO;
import com.example.entity.vo.response.SshSettingsVO;
import com.example.mapper.ClientSshMapper;
import com.example.mapper.struct.ClientStructMapper;
import com.example.service.impl.ClientServiceImpl;
import com.example.utils.CryptoUtils;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Proxy;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSH 设置接口的敏感字段合同测试。
 */
class ClientServiceImplSshSettingsTest {

    private ClientServiceImpl service;
    private ClientSsh stored;
    private final AtomicInteger updateCalls = new AtomicInteger();
    private final AtomicInteger insertCalls = new AtomicInteger();

    /**
     * 初始化内存版 SSH Mapper 与客户端缓存。
     */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        stored = null;
        updateCalls.set(0);
        insertCalls.set(0);
        service = new ClientServiceImpl();

        ClientSshMapper sshMapper = (ClientSshMapper) Proxy.newProxyInstance(
                ClientSshMapper.class.getClassLoader(),
                new Class[]{ClientSshMapper.class},
                (proxy, method, args) -> {
                    if ("selectById".equals(method.getName())) return stored;
                    if ("updateById".equals(method.getName())) {
                        stored = (ClientSsh) args[0];
                        updateCalls.incrementAndGet();
                        return 1;
                    }
                    if ("insert".equals(method.getName())) {
                        stored = (ClientSsh) args[0];
                        insertCalls.incrementAndGet();
                        return 1;
                    }
                    return null;
                });
        ClientStructMapper structMapper = (ClientStructMapper) Proxy.newProxyInstance(
                ClientStructMapper.class.getClassLoader(),
                new Class[]{ClientStructMapper.class},
                (proxy, method, args) -> {
                    if (!"toSshSettingsVO".equals(method.getName())) return null;
                    ClientSsh value = (ClientSsh) args[0];
                    SshSettingsVO vo = new SshSettingsVO();
                    vo.setIp(value.getIp());
                    vo.setPort(value.getPort());
                    vo.setUsername(value.getUsername());
                    return vo;
                });
        ReflectionTestUtils.setField(service, "clientSshMapper", sshMapper);
        ReflectionTestUtils.setField(service, "clientStructMapper", structMapper);
        ReflectionTestUtils.setField(service, "cryptoUtils",
                new CryptoUtils(Base64.getEncoder().encodeToString(new byte[32])));

        Cache<Integer, Client> clientCache = (Cache<Integer, Client>) ReflectionTestUtils.getField(service, "clientIdCache");
        Assertions.assertNotNull(clientCache);
        clientCache.put(42, new Client());
        clientCache.getIfPresent(42).setId(42);
    }

    /**
     * SSH 查询只说明密码是否已配置，响应模型中不能再含有 password 字段。
     */
    @Test
    void getSshSettingShouldNotExposePassword() {
        stored = ssh("192.0.2.10", "ENC:encrypted-password");

        SshSettingsVO result = service.getSshSetting(42);

        Assertions.assertEquals("192.0.2.10", result.getIp());
        Assertions.assertTrue(result.isPasswordConfigured());
        Assertions.assertThrows(NoSuchFieldException.class,
                () -> SshSettingsVO.class.getDeclaredField("password"));
    }

    /**
     * 已有 SSH 设置时空密码应保留原密文，允许只修改地址、端口或用户名。
     */
    @Test
    void blankPasswordShouldKeepExistingCiphertext() {
        stored = ssh("192.0.2.10", "ENC:keep-this-value");
        SshConnectVO request = request("192.0.2.11", "");

        service.saveSshConnection(request);

        Assertions.assertEquals(1, updateCalls.get());
        Assertions.assertEquals(0, insertCalls.get());
        Assertions.assertEquals("ENC:keep-this-value", stored.getPassword());
        Assertions.assertEquals("192.0.2.11", stored.getIp());
    }

    /**
     * 首次保存 SSH 设置时仍必须提供密码，避免创建无法使用的配置。
     */
    @Test
    void initialSshSettingShouldRequirePassword() {
        ResponseStatusException error = Assertions.assertThrows(ResponseStatusException.class,
                () -> service.saveSshConnection(request("192.0.2.10", "")));

        Assertions.assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        Assertions.assertEquals(0, insertCalls.get());
    }

    /**
     * 构造已保存的 SSH 行。
     *
     * @param ip 主机地址
     * @param password 已加密或历史密码字段
     * @return SSH 数据行
     */
    private ClientSsh ssh(String ip, String password) {
        ClientSsh row = new ClientSsh();
        row.setId(42);
        row.setIp(ip);
        row.setPort(22);
        row.setUsername("root");
        row.setPassword(password);
        return row;
    }

    /**
     * 构造 SSH 保存请求。
     *
     * @param ip 主机地址
     * @param password 可选的新密码
     * @return 保存请求
     */
    private SshConnectVO request(String ip, String password) {
        SshConnectVO request = new SshConnectVO();
        request.setId(42);
        request.setIp(ip);
        request.setPort(22);
        request.setUsername("root");
        request.setPassword(password);
        return request;
    }
}
