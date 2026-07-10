package com.example.service;

import com.example.entity.dto.Client;
import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.entity.vo.response.ClientPreviewVO;
import com.example.entity.vo.response.ClientSimpleVO;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 客户端读模型服务，负责批量加载静态详情并组装列表响应。
 */
public interface ClientReadModelService {

    /**
     * 按客户端快照顺序组装管理页预览列表。
     *
     * @param clients           客户端基础信息快照
     * @param runtimeByClientId 当前运行时数据快照
     * @param onlineClientIds   当前在线客户端ID集合
     * @return 客户端预览列表
     */
    List<ClientPreviewVO> listClients(List<Client> clients,
                                      Map<Integer, RuntimeDetailVO> runtimeByClientId,
                                      Set<Integer> onlineClientIds);

    /**
     * 按客户端快照顺序组装权限配置使用的简要列表。
     *
     * @param clients 客户端基础信息快照
     * @return 客户端简要列表
     */
    List<ClientSimpleVO> listSimpleClients(List<Client> clients);
}
