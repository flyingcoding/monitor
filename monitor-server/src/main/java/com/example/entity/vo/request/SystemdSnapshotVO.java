package com.example.entity.vo.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * v1.3：客户端上报的 systemd unit 状态快照（每 10 秒一次）。
 * <p>
 * 接收路径 {@code POST /monitor/systemd}。服务端将 units 列表入 Caffeine 缓存供
 * {@code GET /api/monitor/systemd?clientId=...} 读取，并触发 SSE 推送。
 */
@Data
public class SystemdSnapshotVO {
    /**
     * unit 状态列表。允许空列表（采集模块禁用或所有 unit 解析失败时）。
     */
    @NotNull
    @Size(max = 256)
    @Valid
    private List<SystemdUnitStatVO> units;
}
