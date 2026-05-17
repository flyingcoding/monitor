package com.example.entity.dto;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 公开状态页配置（单行表 {@code id = 1}）。对应 {@code status_page_config} 表。
 *
 * <p>v1.2 仅支持单状态页（PRD Out of Scope 已声明）。{@code clientIds} 是逗号分隔的客户端 ID 列表
 * （同 {@code account.clients} 模式），空字符串 / {@code null} 时含义"所有客户端"或"无任何客户端"由
 * {@code StatusPageService} 显式定义：{@code clientIds == null} → 公开所有，
 * {@code clientIds == ""} → 公开零个（管理员主动留空）。
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
     * 公开客户端 ID 列表，逗号分隔。{@code null} 表示尚未配置（公开所有），
     * 空字符串表示明确公开零个客户端。
     */
    String clientIds;

    /**
     * 状态页是否启用：禁用时 {@code /api/status/summary} 仍返回标题（仅 clients 为空）。
     */
    Boolean enabled;

    Date updatedAt;
}
