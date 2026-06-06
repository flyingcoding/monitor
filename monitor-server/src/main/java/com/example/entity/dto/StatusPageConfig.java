package com.example.entity.dto;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 公开状态页配置（单行表 {@code id = 1}）。对应 {@code status_page_config} 表。
 *
 * <p>v1.2 仅支持单状态页（PRD Out of Scope 已声明）。{@code clientIds} 是逗号分隔的客户端 ID 列表
 * （同 {@code account.clients} 模式）。当前采用 default-deny：{@code clientIds == null} 或空字符串
 * 都表示未公开任何客户端，管理员必须显式选择客户端后才会对访客展示。
 */
@Data
@TableName("status_page_config")
public class StatusPageConfig {

    @TableId
    Integer id;

    /**
     * 状态页大标题。
     */
    String title;

    /**
     * 状态页副标题（可空）。
     */
    String subtitle;

    /**
     * 顶部徽章主题色（可空），如 {@code #10b981}。
     */
    String brandColor;

    /**
     * 状态页 Logo URL（可空）。
     */
    String logoUrl;

    /**
     * 公开客户端 ID 列表，逗号分隔。{@code null} 或空字符串都表示未公开任何客户端。
     */
    String clientIds;

    /**
     * 状态页是否启用：禁用时 {@code /api/status/summary} 仍返回标题（仅 clients 为空）。
     */
    Boolean enabled;

    Date updatedAt;
}
