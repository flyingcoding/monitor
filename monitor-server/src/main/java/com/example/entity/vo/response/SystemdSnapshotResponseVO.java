package com.example.entity.vo.response;

import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * v1.3：systemd unit 快照响应（前端 systemd tab 查询用）。
 * <p>
 * 由 {@code GET /api/monitor/systemd?clientId=...} 返回，{@link #units} 为最近一次上报的 unit 状态列表，
 * {@link #updatedAt} 为缓存写入时间戳。缓存有效期 30 秒（与客户端 10s 上报周期 + 网络抖动余量匹配）。
 */
@Data
public class SystemdSnapshotResponseVO {
    /** 客户端ID。 */
    private Integer clientId;
    /** unit 状态列表。 */
    private List<SystemdUnitStatResponseVO> units;
    /** 最近上报时间戳。 */
    private Date updatedAt;

    /**
     * 单个 unit 状态（响应字段，与 {@link com.example.entity.vo.request.SystemdUnitStatVO}
     * 字段对齐，避免请求 VO 泄漏到响应层）。
     */
    @Data
    public static class SystemdUnitStatResponseVO {
        private String name;
        private String loadState;
        private String activeState;
        private String subState;
        private String description;
        private boolean healthy;
    }
}
