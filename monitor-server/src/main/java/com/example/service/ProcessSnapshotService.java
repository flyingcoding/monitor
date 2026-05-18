package com.example.service;

import com.example.entity.vo.request.ProcessSnapshotVO;
import com.example.entity.vo.response.ProcessSnapshotResponseVO;

/**
 * v1.3：进程快照服务。
 * <p>
 * 接收客户端上报的 {@link ProcessSnapshotVO}，30s Caffeine 缓存供前端"进程"tab 读取；
 * 同时通过 {@link com.example.config.SseEventBus#publishProcessSnapshot} 推送实时事件。
 */
public interface ProcessSnapshotService {

    /**
     * 接收客户端上报的进程快照，更新缓存并广播 SSE 事件。
     *
     * @param clientId 客户端ID
     * @param vo 上报载荷
     */
    void ingest(Integer clientId, ProcessSnapshotVO vo);

    /**
     * 查询客户端最近一次进程快照。
     *
     * @param clientId 客户端ID
     * @return 快照响应；缓存未命中或客户端未上报时返回 null
     */
    ProcessSnapshotResponseVO getLatest(Integer clientId);
}
