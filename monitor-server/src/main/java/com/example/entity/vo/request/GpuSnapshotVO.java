package com.example.entity.vo.request;

import lombok.Data;

import java.util.List;

/**
 * 客户端上报 NVIDIA GPU 快照。
 * <p>
 * v1.3：客户端 GpuCollector 每 10 秒采集一次后通过 {@code POST /monitor/gpu} 上报，
 * 服务端写入 Caffeine 缓存 + SSE 推送，前端"GPU"tab 实时展示。
 */
@Data
public class GpuSnapshotVO {
    /** GPU 列表；客户端无 GPU 时上报空列表（用于服务端清空缓存）。 */
    private List<GpuStatVO> gpus;
}
