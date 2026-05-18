package com.example.entity.vo.response;

import com.example.entity.vo.request.GpuStatVO;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * GPU 快照查询响应。
 * <p>
 * v1.3：admin / 子账户通过 {@code GET /api/monitor/gpu?clientId=...} 拉取最新 GPU 快照，
 * 服务端从 Caffeine 缓存返回；缓存未命中（30s TTL 过期 / 客户端从未上报）时返回 {@code null}。
 */
@Data
public class GpuSnapshotResponseVO {
    /** 客户端ID。 */
    private Integer clientId;
    /** 数据采集时间（服务端落缓存时刻）。 */
    private Instant updatedAt;
    /** GPU 列表；客户端无 GPU 时为空列表。 */
    private List<GpuStatVO> gpus;
}
