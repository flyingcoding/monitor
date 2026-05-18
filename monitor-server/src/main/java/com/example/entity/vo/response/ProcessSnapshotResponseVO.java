package com.example.entity.vo.response;

import lombok.Data;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * v1.3：进程快照响应（前端"进程"tab 查询用）。
 * <p>
 * 由 {@code GET /api/monitor/process?clientId=...} 返回，{@link #top10ByCpu} /
 * {@link #top10ByMemory} 为最近一次上报的 Top N 进程列表，
 * {@link #watchedPatterns} 为关键进程匹配状态，
 * {@link #updatedAt} 为缓存写入时间戳。缓存有效期 30 秒（与客户端 10s 上报周期 + 网络抖动余量匹配）。
 */
@Data
public class ProcessSnapshotResponseVO {
    /** 客户端ID。 */
    private Integer clientId;
    /** 按 CPU 倒序排列的 Top N 进程列表。 */
    private List<ProcessInfoResponseVO> top10ByCpu;
    /** 按 RSS 物理内存倒序排列的 Top N 进程列表。 */
    private List<ProcessInfoResponseVO> top10ByMemory;
    /** 配置的关键进程 pattern → 是否匹配到至少一个运行进程。 */
    private Map<String, Boolean> watchedPatterns;
    /** 客户端采集时间戳。 */
    private long timestamp;
    /** 服务端缓存写入时间戳。 */
    private Date updatedAt;

    /**
     * 单条进程信息（响应字段，与 {@link com.example.entity.vo.request.ProcessSnapshotVO.ProcessInfoVO}
     * 字段对齐，避免请求 VO 泄漏到响应层）。
     */
    @Data
    public static class ProcessInfoResponseVO {
        private String name;
        private int pid;
        private double cpuPercent;
        private long memoryBytes;
    }
}
