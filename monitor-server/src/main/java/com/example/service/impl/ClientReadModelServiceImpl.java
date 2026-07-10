package com.example.service.impl;

import com.example.entity.dto.Client;
import com.example.entity.dto.ClientDetail;
import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.entity.vo.response.ClientPreviewVO;
import com.example.entity.vo.response.ClientSimpleVO;
import com.example.mapper.ClientDetailMapper;
import com.example.mapper.struct.ClientStructMapper;
import com.example.service.ClientReadModelService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 客户端读模型默认实现，使用单次批量查询加载客户端详情。
 */
@Service
public class ClientReadModelServiceImpl implements ClientReadModelService {

    private final ClientDetailMapper clientDetailMapper;
    private final ClientStructMapper clientStructMapper;

    /**
     * 创建客户端读模型服务。
     *
     * @param clientDetailMapper 客户端详情Mapper
     * @param clientStructMapper 客户端结构映射器
     */
    public ClientReadModelServiceImpl(ClientDetailMapper clientDetailMapper,
                                      ClientStructMapper clientStructMapper) {
        this.clientDetailMapper = clientDetailMapper;
        this.clientStructMapper = clientStructMapper;
    }

    /**
     * 批量加载客户端详情并合并在线运行时数据。
     *
     * @param clients           客户端基础信息快照
     * @param runtimeByClientId 当前运行时数据快照
     * @param onlineClientIds   当前在线客户端ID集合
     * @return 客户端预览列表
     */
    @Override
    public List<ClientPreviewVO> listClients(List<Client> clients,
                                             Map<Integer, RuntimeDetailVO> runtimeByClientId,
                                             Set<Integer> onlineClientIds) {
        if (clients.isEmpty()) {
            return List.of();
        }
        Map<Integer, ClientDetail> detailsByClientId = loadDetailsByClientId(clients);
        return clients.stream().map(client -> {
            ClientPreviewVO vo = clientStructMapper.toPreviewVO(client);
            copyDetail(detailsByClientId.get(client.getId()), vo);
            if (onlineClientIds.contains(client.getId())) {
                RuntimeDetailVO runtime = runtimeByClientId.get(client.getId());
                if (runtime != null) {
                    BeanUtils.copyProperties(runtime, vo);
                }
                vo.setOnline(true);
            }
            return vo;
        }).toList();
    }

    /**
     * 批量加载客户端详情并组装简要列表。
     *
     * @param clients 客户端基础信息快照
     * @return 客户端简要列表
     */
    @Override
    public List<ClientSimpleVO> listSimpleClients(List<Client> clients) {
        if (clients.isEmpty()) {
            return List.of();
        }
        Map<Integer, ClientDetail> detailsByClientId = loadDetailsByClientId(clients);
        return clients.stream().map(client -> {
            ClientSimpleVO vo = clientStructMapper.toSimpleVO(client);
            copyDetail(detailsByClientId.get(client.getId()), vo);
            return vo;
        }).toList();
    }

    /**
     * 使用一次主键批量查询加载详情，并按客户端ID建立索引。
     *
     * @param clients 客户端基础信息快照
     * @return 客户端ID到详情的映射
     */
    private Map<Integer, ClientDetail> loadDetailsByClientId(List<Client> clients) {
        List<Integer> clientIds = clients.stream()
                .map(Client::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (clientIds.isEmpty()) {
            return Map.of();
        }
        return clientDetailMapper.selectByIds(clientIds).stream()
                .filter(Objects::nonNull)
                .filter(detail -> detail.getId() != null)
                .collect(Collectors.toMap(ClientDetail::getId, Function.identity(), (first, ignored) -> first));
    }

    /**
     * 将可选的客户端静态详情补充到目标响应对象。
     *
     * @param detail 客户端详情，允许为空
     * @param target 目标响应对象
     */
    private void copyDetail(ClientDetail detail, Object target) {
        if (detail != null) {
            BeanUtils.copyProperties(detail, target);
        }
    }
}
