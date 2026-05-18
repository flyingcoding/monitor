package com.example.entity.vo.response;

import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * v1.3：SMART 磁盘健康快照响应（前端 SMART tab 查询用）。
 * <p>
 * 由 {@code GET /api/monitor/smart?clientId=...} 返回，{@link #disks} 为最近一次上报的磁盘 SMART 状态列表，
 * {@link #updatedAt} 为缓存写入时间戳。缓存有效期 30 秒（与客户端 10s 上报周期 + 网络抖动余量匹配）。
 */
@Data
public class SmartSnapshotResponseVO {
    /** 客户端ID。 */
    private Integer clientId;
    /** 磁盘 SMART 状态列表。 */
    private List<SmartStatResponseVO> disks;
    /** 最近上报时间戳。 */
    private Date updatedAt;

    /**
     * 单磁盘 SMART 响应字段，与 {@link com.example.entity.vo.request.SmartStatVO}
     * 字段对齐，避免请求 VO 泄漏到响应层。
     */
    @Data
    public static class SmartStatResponseVO {
        private String device;
        private String modelName;
        private boolean nvme;
        private Long reallocatedSector;
        private Long currentPending;
        private Long offlineUncorrectable;
        private Long mediaErrors;
        private Integer temperatureCelsius;
        private boolean critical;
    }
}
