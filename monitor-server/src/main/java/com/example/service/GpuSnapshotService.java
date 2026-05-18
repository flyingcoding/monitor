package com.example.service;

import com.example.entity.vo.request.GpuSnapshotVO;
import com.example.entity.vo.response.GpuSnapshotResponseVO;

/**
 * v1.3：NVIDIA GPU 快照服务。
 * <p>
 * 接收客户端上报的 {@link GpuSnapshotVO}，30s Caffeine 缓存供前端"GPU"tab 读取；
 * 同时通过 {@link com.example.config.SseEventBus#publishGpuSnapshot} 推送实时事件。
 */
public interface GpuSnapshotService {

    /**
     * 接收客户端上报的 GPU 快照，更新缓存并广播 SSE 事件。
     *
     * @param clientId 客户端ID
     * @param vo 上报载荷
     */
    void ingest(Integer clientId, GpuSnapshotVO vo);

    /**
     * 查询客户端最近一次 GPU 快照。
     *
     * @param clientId 客户端ID
     * @return 快照响应；缓存未命中或客户端未上报时返回 null
     */
    GpuSnapshotResponseVO getLatest(Integer clientId);
}
