package com.example.entity.vo.response;

import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 管理员视角的状态页配置 VO。
 *
 * <p>包含完整的配置字段以及候选客户端列表（仅 id / 内部 name / 公开 displayName），
 * 便于前端管理页直接渲染多选下拉框。
 */
@Data
public class StatusPageConfigVO {

    Integer id;

    String title;

    String subtitle;

    String brandColor;

    String logoUrl;

    /**
     * 公开客户端 ID 列表（反序列化自 CSV）；{@code null} 表示"未配置"（默认公开所有）。
     */
    List<Integer> clientIds;

    Boolean enabled;

    Date updatedAt;

    /**
     * 候选客户端列表，供前端管理页选择。
     */
    List<StatusPageCandidateClientVO> availableClients;

    /**
     * 候选客户端字段：仅展示选择必需的内容。即便有更多字段（IP、OS 等）也由 API 决定不下放。
     */
    @Data
    public static class StatusPageCandidateClientVO {
        Integer id;
        String name;
        String displayName;
    }
}
