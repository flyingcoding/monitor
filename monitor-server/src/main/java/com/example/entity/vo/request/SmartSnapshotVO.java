package com.example.entity.vo.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * v1.3：客户端上报的 SMART 磁盘健康快照（每 10 秒一次）。
 * <p>
 * 接收路径 {@code POST /monitor/smart}。服务端将 disks 列表入 Caffeine 缓存供
 * {@code GET /api/monitor/smart?clientId=...} 读取，并触发 SSE 推送。
 */
@Data
public class SmartSnapshotVO {
    /**
     * 磁盘 SMART 状态列表。允许空列表（采集模块禁用或所有 device 解析失败时）。
     */
    @NotNull
    @Size(max = 64)
    @Valid
    private List<SmartStatVO> disks;
}
