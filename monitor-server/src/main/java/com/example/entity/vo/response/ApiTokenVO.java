package com.example.entity.vo.response;

import lombok.Data;

import java.util.Date;

/**
 * API Token 列表 / 单查响应 VO。
 *
 * <p>永远不会返回 {@code tokenHash}（密文）或原始明文 token；仅返回 {@code prefixTail} 用于
 * 在 UI 中识别（如 "mtk_aB3D…xY9z"）。
 */
@Data
public class ApiTokenVO {

    Long id;

    String name;

    /**
     * 形如 "mtk_aB3D…xY9z" 的展示字段；不足以重建明文。
     */
    String prefixTail;

    /**
     * {@code readonly} / {@code readwrite}。
     */
    String scope;

    Date expiresAt;

    Date lastUsedAt;

    String lastUsedIp;

    Date createdAt;
}
