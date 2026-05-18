package com.example.entity.vo.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * v1.3：客户端上报的进程快照（每 10 秒一次）。
 * <p>
 * 接收路径 {@code POST /monitor/process}。服务端将快照入 Caffeine 缓存供
 * {@code GET /api/monitor/process?clientId=...} 读取，并触发 SSE 推送。
 */
@Data
public class ProcessSnapshotVO {
    /** 客户端采集时间戳（毫秒）。 */
    private long timestamp;

    /** 按 CPU 倒序排列的 Top N 进程列表（默认 N=10，最大 50）。 */
    @NotNull
    @Size(max = 50)
    @Valid
    private List<ProcessInfoVO> top10ByCpu;

    /** 按 RSS 物理内存倒序排列的 Top N 进程列表。 */
    @NotNull
    @Size(max = 50)
    @Valid
    private List<ProcessInfoVO> top10ByMemory;

    /** 配置的关键进程 pattern → 是否匹配到至少一个运行进程；允许空。 */
    @NotNull
    private Map<String, Boolean> watchedPatterns;

    /**
     * 单条进程信息（请求 VO）。字段对齐客户端 {@code ProcessSnapshot.ProcessInfo}。
     */
    @Data
    public static class ProcessInfoVO {
        /** 进程名（OSHI {@code OSProcess.getName()}）。允许空字符串（OSHI 在低权限下可能取不到）。 */
        private String name;
        /** 进程 ID。 */
        private int pid;
        /** CPU 使用率（0~1）。 */
        private double cpuPercent;
        /** 物理内存（RSS）字节数。 */
        private long memoryBytes;
    }
}

