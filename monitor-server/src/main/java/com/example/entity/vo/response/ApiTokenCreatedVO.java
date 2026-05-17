package com.example.entity.vo.response;

import lombok.Data;

/**
 * 创建 API Token 时的响应 VO。仅在 {@code POST /api/tokens} 与 {@code POST /api/tokens/{id}/rotate}
 * 成功时返回，并且 {@code token} 字段是用户能见到完整明文 token 的<b>唯一一次机会</b>。
 *
 * <p>客户端必须在此时复制 token 自行保存；服务器只存 HMAC-SHA256 哈希，无法事后重发明文。
 */
@Data
public class ApiTokenCreatedVO {

    /**
     * 完整明文 token，例如 {@code mtk_aB3D...xY9z}（36 字符）。仅创建时返回一次。
     */
    String token;

    /**
     * 与列表视图字段一致的 token 元数据快照。
     */
    ApiTokenVO meta;
}
