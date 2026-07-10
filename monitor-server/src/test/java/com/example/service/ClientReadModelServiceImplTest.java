package com.example.service;

import com.example.entity.dto.Client;
import com.example.entity.dto.ClientDetail;
import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.entity.vo.response.ClientPreviewVO;
import com.example.entity.vo.response.ClientSimpleVO;
import com.example.mapper.ClientDetailMapper;
import com.example.mapper.struct.ClientStructMapper;
import com.example.service.impl.ClientReadModelServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 客户端读模型批量查询与字段组装合同测试。
 */
class ClientReadModelServiceImplTest {

    private ClientDetailMapper clientDetailMapper;
    private ClientStructMapper clientStructMapper;
    private ClientReadModelServiceImpl service;

    /**
     * 初始化Mapper桩和待测服务。
     */
    @BeforeEach
    void setUp() {
        clientDetailMapper = mock(ClientDetailMapper.class);
        clientStructMapper = mock(ClientStructMapper.class);
        service = new ClientReadModelServiceImpl(clientDetailMapper, clientStructMapper);
        when(clientStructMapper.toPreviewVO(any(Client.class))).thenAnswer(invocation -> {
            Client client = invocation.getArgument(0);
            ClientPreviewVO vo = new ClientPreviewVO();
            vo.setId(client.getId());
            vo.setName(client.getName());
            vo.setLocation(client.getLocation());
            return vo;
        });
        when(clientStructMapper.toSimpleVO(any(Client.class))).thenAnswer(invocation -> {
            Client client = invocation.getArgument(0);
            ClientSimpleVO vo = new ClientSimpleVO();
            vo.setId(client.getId());
            vo.setName(client.getName());
            vo.setLocation(client.getLocation());
            return vo;
        });
    }

    /**
     * 预览列表应只批量查询一次，并且仅为在线客户端叠加运行时数据。
     */
    @Test
    void listClientsShouldBatchLoadDetailsAndOverlayRuntimeOnlyWhenOnline() {
        Client first = client(1, "first");
        Client second = client(2, "second");
        ClientDetail firstDetail = detail(1, "Linux One", "192.0.2.1");
        ClientDetail secondDetail = detail(2, "Linux Two", "192.0.2.2");
        when(clientDetailMapper.selectByIds(anyCollection())).thenAnswer(invocation -> {
            Collection<?> ids = invocation.getArgument(0);
            assertEquals(List.of(1, 2), new ArrayList<>(ids));
            return List.of(secondDetail, firstDetail);
        });

        RuntimeDetailVO onlineRuntime = runtime(11.5);
        RuntimeDetailVO staleOfflineRuntime = runtime(99.9);
        List<ClientPreviewVO> result = service.listClients(
                List.of(first, second),
                Map.of(1, onlineRuntime, 2, staleOfflineRuntime),
                Set.of(1));

        assertEquals(List.of(1, 2), result.stream().map(ClientPreviewVO::getId).toList());
        assertEquals("Linux One", result.get(0).getOsName());
        assertEquals("192.0.2.1", result.get(0).getIp());
        assertTrue(result.get(0).isOnline());
        assertEquals(11.5, result.get(0).getCpuUsage());
        assertEquals("Linux Two", result.get(1).getOsName());
        assertFalse(result.get(1).isOnline());
        assertEquals(0.0, result.get(1).getCpuUsage(), "离线客户端不得叠加陈旧运行时数据");
        verify(clientDetailMapper, times(1)).selectByIds(anyCollection());
        verify(clientDetailMapper, never()).selectById(any());
    }

    /**
     * 简要列表应保持输入顺序，并允许部分客户端缺少详情行。
     */
    @Test
    void listSimpleClientsShouldPreserveOrderAndAllowMissingDetails() {
        Client second = client(2, "second");
        Client first = client(1, "first");
        when(clientDetailMapper.selectByIds(anyCollection()))
                .thenReturn(List.of(detail(2, "Linux Two", "192.0.2.2")));

        List<ClientSimpleVO> result = service.listSimpleClients(List.of(second, first));

        assertEquals(List.of(2, 1), result.stream().map(ClientSimpleVO::getId).toList());
        assertEquals("Linux Two", result.get(0).getOsName());
        assertEquals("192.0.2.2", result.get(0).getIp());
        assertNull(result.get(1).getOsName());
        assertEquals("first", result.get(1).getName());
        verify(clientDetailMapper, times(1)).selectByIds(anyCollection());
        verify(clientDetailMapper, never()).selectById(any());
    }

    /**
     * 空客户端列表应直接返回空结果且不访问Mapper或结构映射器。
     */
    @Test
    void emptyClientListsShouldSkipDatabaseQueries() {
        assertTrue(service.listClients(List.of(), Map.of(), Set.of()).isEmpty());
        assertTrue(service.listSimpleClients(List.of()).isEmpty());

        verifyNoInteractions(clientDetailMapper, clientStructMapper);
    }

    /**
     * 构造客户端基础信息。
     *
     * @param id   客户端ID
     * @param name 客户端名称
     * @return 客户端实体
     */
    private Client client(int id, String name) {
        return new Client(id, name, "token-" + id, "cn", "node", new Date(), null);
    }

    /**
     * 构造客户端静态详情。
     *
     * @param id     客户端ID
     * @param osName 操作系统名称
     * @param ip     客户端IP
     * @return 客户端详情实体
     */
    private ClientDetail detail(int id, String osName, String ip) {
        ClientDetail detail = new ClientDetail();
        detail.setId(id);
        detail.setOsName(osName);
        detail.setIp(ip);
        detail.setCpuCore(id * 2);
        detail.setMemory(id * 4.0);
        return detail;
    }

    /**
     * 构造当前或陈旧的运行时数据。
     *
     * @param cpuUsage CPU使用率
     * @return 运行时数据
     */
    private RuntimeDetailVO runtime(double cpuUsage) {
        RuntimeDetailVO runtime = new RuntimeDetailVO();
        runtime.setCpuUsage(cpuUsage);
        runtime.setTimestamp(System.currentTimeMillis());
        return runtime;
    }
}
