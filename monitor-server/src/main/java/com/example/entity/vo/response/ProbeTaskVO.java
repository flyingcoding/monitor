package com.example.entity.vo.response;

import lombok.Data;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 服务探测任务响应 VO（管理员视角）。
 *
 * <p>敏感字段策略（与 {@code NotificationChannelVO.maskedConfig} 一致）：
 * <ul>
 *   <li>{@code headers}：Map 中所有值返回 {@code "***"}，仅暴露 key 让前端展示"已配置哪些 Header"；
 *       若任一 header 解密失败或不存在，{@code headers} 返回 null。</li>
 *   <li>{@code basicAuthPassword} 不返回，仅暴露 {@code hasBasicAuthPassword} 布尔。</li>
 * </ul>
 */
@Data
public class ProbeTaskVO {

    private Long id;
    private String name;
    private String type;
    private String target;
    private Integer intervalSec;
    private Integer timeoutSec;
    private Integer expectedStatusCode;
    private String expectedBodyPattern;

    /** 已脱敏的 headers：每个 value 替换为 {@code "***"}；为空表示无 Header。 */
    private Map<String, String> headers;

    private String basicAuthUsername;

    /** 是否已设置 Basic Auth 密码；前端据此渲染"已配置 / 留空保留"提示。 */
    private Boolean hasBasicAuthPassword;

    private Integer sslWarnDays;
    private Integer consecutiveFailuresThreshold;

    /** 通知通道 ID 列表（已从存储的 JSON 字符串反序列化）。 */
    private List<Long> channelIds;

    private Boolean enabled;
    private Date createdAt;
    private Date updatedAt;
}
