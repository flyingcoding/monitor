package com.example.config;

import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.entity.vo.response.AlertHistoryVO;
import com.example.entity.vo.response.GpuSnapshotResponseVO;
import com.example.entity.vo.response.ProcessSnapshotResponseVO;
import com.example.entity.vo.response.SmartSnapshotResponseVO;
import com.example.entity.vo.response.SystemdSnapshotResponseVO;

/**
 * SSE 事件总线抽象。
 *
 * 默认实现为单机内存推送，后续可替换为 Redis Pub/Sub 等跨实例实现。
 */
public interface SseEventBus {

    /**
     * 发布客户端列表变更事件。
     */
    void publishClientList();

    /**
     * 发布指定客户端的运行时数据事件。
     *
     * @param clientId 客户端ID
     * @param vo 运行时数据
     */
    void publishRuntime(int clientId, RuntimeDetailVO vo);

    /**
     * 发布新触发的告警事件，按订阅者权限过滤后推送 {@code alert-fired} 事件给前端。
     *
     * @param vo 告警历史 VO
     */
    void publishAlertFired(AlertHistoryVO vo);

    /**
     * v1.3：发布指定客户端最新的 systemd 快照事件，前端"systemd"tab 据此实时刷新 unit 状态。
     *
     * @param clientId 客户端ID
     * @param vo 快照响应 VO
     */
    void publishSystemdSnapshot(int clientId, SystemdSnapshotResponseVO vo);

    /**
     * v1.3：发布指定客户端最新的 SMART 快照事件，前端"SMART"tab 据此实时刷新磁盘健康状态。
     *
     * @param clientId 客户端ID
     * @param vo 快照响应 VO
     */
    void publishSmartSnapshot(int clientId, SmartSnapshotResponseVO vo);

    /**
     * v1.3：发布指定客户端最新的进程快照事件，前端"进程"tab 据此实时刷新 Top N 与 watched pattern 状态。
     *
     * @param clientId 客户端ID
     * @param vo 快照响应 VO
     */
    void publishProcessSnapshot(int clientId, ProcessSnapshotResponseVO vo);

    /**
     * v1.3：发布指定客户端最新的 NVIDIA GPU 快照事件，前端"GPU"tab 据此实时刷新每张卡的利用率/温度/功耗。
     *
     * @param clientId 客户端ID
     * @param vo 快照响应 VO
     */
    void publishGpuSnapshot(int clientId, GpuSnapshotResponseVO vo);
}
