package com.example.service;

import com.example.entity.vo.request.SystemdSnapshotVO;
import com.example.entity.vo.response.SystemdSnapshotResponseVO;

/**
 * v1.3：systemd unit 状态快照服务。
 * <p>
 * 接收客户端上报的 {@link SystemdSnapshotVO}，30s Caffeine 缓存供前端 systemd tab 读取；
 * 同时通过 {@link com.example.config.SseEventBus#publishSystemdSnapshot} 推送实时事件。
 */
public interface SystemdSnapshotService {

    /**
     * 接收客户端上报的 systemd 快照，更新缓存并广播 SSE 事件。
     *
     * @param clientId 客户端ID
     * @param vo 上报载荷
     */
    void ingest(Integer clientId, SystemdSnapshotVO vo);

    /**
     * 查询客户端最近一次 systemd 快照。
     *
     * @param clientId 客户端ID
     * @return 快照响应；缓存未命中或客户端未上报时返回 null
     */
    SystemdSnapshotResponseVO getLatest(Integer clientId);
}
