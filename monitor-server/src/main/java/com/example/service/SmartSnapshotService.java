package com.example.service;

import com.example.entity.vo.request.SmartSnapshotVO;
import com.example.entity.vo.response.SmartSnapshotResponseVO;

/**
 * v1.3：SMART 磁盘健康快照服务。
 * <p>
 * 接收客户端上报的 {@link SmartSnapshotVO}，30s Caffeine 缓存供前端 SMART tab 读取；
 * 同时通过 {@link com.example.config.SseEventBus#publishSmartSnapshot} 推送实时事件。
 */
public interface SmartSnapshotService {

    /**
     * 接收客户端上报的 SMART 快照，更新缓存并广播 SSE 事件。
     *
     * @param clientId 客户端ID
     * @param vo 上报载荷
     */
    void ingest(Integer clientId, SmartSnapshotVO vo);

    /**
     * 查询客户端最近一次 SMART 快照。
     *
     * @param clientId 客户端ID
     * @return 快照响应；缓存未命中或客户端未上报时返回 null
     */
    SmartSnapshotResponseVO getLatest(Integer clientId);
}
