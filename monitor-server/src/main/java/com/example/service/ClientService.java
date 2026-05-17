package com.example.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.entity.dto.Client;
import com.example.entity.dto.ClientSsh;
import com.example.entity.vo.request.*;
import com.example.entity.vo.response.*;

import java.util.List;

/**
 * @program: monitor
 * @description: 客户端服务接口
 * @author: 王贝强
 * @create: 2024-07-13 16:22
 */
public interface ClientService extends IService<Client>{
    String getToken();
    boolean registerClient(String token);
    void updateHeartbeat(Client client);
    void clientOffline(Client client);
    Client findClientById(int id);
    Client findClientByToken(String token);
    void updateClientDetail(ClientDetailVO vo,Client client);
    void updateRuntimeDetail(RuntimeDetailVO vo, Client client);
    List<ClientPreviewVO> listClients();
    List<ClientSimpleVO> listSimpleClients();
    void renameClient(RenameClientVO vo);
    ClientDetailsVO clientDetails(int clientId);
    void renameNode(RenameNodeVO vo);
    RuntimeHistoryVO clientRuntimeDetailsHistory(int clientId);
    RuntimeDetailVO clientRuntimeDetailsNow(int clientId);
    void deleteClient(int clientId);
    void saveSshConnection(SshConnectVO vo);
    SshSettingsVO getSshSetting(int clientId);

    /**
     * 查询需要主动健康探测的客户端。
     *
     * @param staleThresholdMs 过期阈值毫秒
     * @return 待探测客户端列表
     */
    List<Client> listHealthCheckCandidates(long staleThresholdMs);

    /**
     * 查询指定客户端的SSH配置。
     *
     * @param clientId 客户端ID
     * @return SSH配置
     */
    ClientSsh findClientSsh(int clientId);

    /**
     * 强制将客户端标记为离线。
     *
     * @param clientId 客户端ID
     */
    void forceClientOffline(int clientId);

    /**
     * 判断指定客户端是否在线。
     *
     * <p>判定依据：最近一次心跳或运行时数据更新时间在 60 秒内。
     * 公开状态页等只读消费者复用此方法以保持与管理后台一致的在线判定语义。
     *
     * @param clientId 客户端ID
     * @return 是否在线
     */
    boolean isClientOnline(int clientId);

    /**
     * 查询客户端最近一次心跳距今的秒数。
     *
     * <p>公开状态页用来回显"上次见到 N 秒前"；未上线过的客户端返回 {@code null}。
     *
     * @param clientId 客户端ID
     * @return 距今秒数，或 {@code null} 表示无心跳记录
     */
    Long lastSeenSecondsAgo(int clientId);
}
