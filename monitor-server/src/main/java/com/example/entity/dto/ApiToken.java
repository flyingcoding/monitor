package com.example.entity.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * REST API Token 实体。对应 {@code api_token} 表。
 *
 * <p>{@code tokenHash} 列存放对完整明文 token（前缀 {@code mtk_} + 32 字符 base62 主体）计算的
 * HMAC-SHA256（URL-safe base64，无填充）。明文 token 仅在 {@code POST /api/tokens} 创建时一次性返回，
 * 不会落盘；丢失只能重新生成。
 *
 * <p>{@code prefixTail} 是 "mtk_xxxx…abcd" 形式的可识别尾部，用于列表展示但不足以重建明文。
 */
@Data
@TableName("api_token")
public class ApiToken {

    @TableId(type = IdType.AUTO)
    Long id;

    Integer accountId;

    /**
     * 用户自定义的 token 名字，用于在管理页区分用途（如 "CI/CD"、"backup script"）。
     */
    String name;

    /**
     * HMAC-SHA256(原始明文 token) 的 URL-safe base64 表示，无填充。最长 {@code VARCHAR(128)}。
     */
    String tokenHash;

    /**
     * 展示用尾部，例如 "mtk_aB3D…xY9z"（前缀 + 前 4 + … + 末 4）。
     */
    String prefixTail;

    /**
     * 二档作用域：{@code readonly} 仅放行 GET/HEAD，{@code readwrite} 放行所有方法。
     */
    String scope;

    /**
     * 过期时间；NULL 表示不过期。
     */
    Date expiresAt;

    /**
     * 最近一次成功鉴权时间。异步更新 + 60 秒节流，避免每请求一次 UPDATE。
     */
    Date lastUsedAt;

    /**
     * 最近一次成功鉴权的客户端 IP。覆盖式记录，仅保留最新一次。
     */
    String lastUsedIp;

    Date createdAt;
}
