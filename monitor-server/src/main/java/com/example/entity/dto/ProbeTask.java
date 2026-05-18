package com.example.entity.dto;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 服务探测任务实体。对应 v1.3 Flyway V4 中的 {@code probe_task} 表。
 *
 * <p>支持 HTTP / TCP / ICMP 三种探测类型；HTTP 支持 Custom Headers 与 Basic Auth：
 * <ul>
 *   <li>{@code headersEnc}：JSON 字符串加密后（{@code ENC:...} 前缀，由 {@link com.example.utils.CryptoUtils} 处理）的请求头集合；</li>
 *   <li>{@code basicAuthPasswordEnc}：Basic Auth 密码密文，与 SSH 凭证 / OIDC client_secret 同款 AES-256-GCM。</li>
 * </ul>
 *
 * <p>{@code channelIds}：通知通道 ID 列表，存储为 JSON 数组字符串（如 {@code "[1,2,3]"}），
 * 表结构是 VARCHAR(255) 而非 JSON 列；Service 层自行解析为 {@code List<Long>} 用于投递。
 *
 * <p>更新策略说明：可空字段（{@code clientId} 不适用此表，但 {@code expectedStatusCode} /
 * {@code expectedBodyPattern} / {@code headersEnc} / {@code basicAuthUsername} /
 * {@code basicAuthPasswordEnc} / {@code channelIds}）允许用户在编辑时清空，因此标
 * {@link FieldStrategy#ALWAYS}，确保 MyBatis-Plus 在 updateById 时发送 {@code SET col=NULL}。
 */
@Data
@TableName("probe_task")
public class ProbeTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务名称，唯一标识；前端列表展示。 */
    private String name;

    /** 探测类型：{@code http} / {@code tcp} / {@code icmp}。 */
    private String type;

    /**
     * 探测目标：
     * <ul>
     *   <li>HTTP：完整 URL，如 {@code https://api.example.com/health}</li>
     *   <li>TCP：{@code host:port}，如 {@code db.example.com:3306}</li>
     *   <li>ICMP：主机名或 IP，如 {@code 192.168.1.1}</li>
     * </ul>
     */
    private String target;

    /** 探测周期（秒），调度器按此值间隔执行；下限 10 秒避免过频。 */
    private Integer intervalSec;

    /** 单次探测超时（秒），HTTP 连接 + 读取总耗时上限。 */
    private Integer timeoutSec;

    /** HTTP 期望状态码；为空表示默认接受 2xx。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer expectedStatusCode;

    /** HTTP 期望响应体正则；为空表示不校验响应体。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String expectedBodyPattern;

    /**
     * HTTP Custom Headers（JSON 字符串）的密文。
     * <p>明文示例：{@code {"Authorization":"Bearer xxx","X-Token":"abc"}}。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String headersEnc;

    /** Basic Auth 用户名（明文存储，与 OIDC clientId 同语义）。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String basicAuthUsername;

    /** Basic Auth 密码的密文。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String basicAuthPasswordEnc;

    /** SSL 证书剩余天数告警阈值；默认 30。仅 HTTPS 探测使用。 */
    private Integer sslWarnDays;

    /** 连续失败次数告警阈值（默认 2）；达到阈值时投递 AlertEvent。 */
    private Integer consecutiveFailuresThreshold;

    /**
     * 通知通道 ID 列表（JSON 数组字符串）。例如 {@code "[1,2]"}。
     * 表列类型为 VARCHAR(255) —— 与 alert_rule.channel_ids 的 JSON 列实现方式不同，
     * 这里走纯字符串存储 + Service 层手动 Jackson 解析。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String channelIds;

    /** 是否启用；禁用时 ProbeScheduler 跳过调度。 */
    private Boolean enabled;

    private Date createdAt;

    private Date updatedAt;
}
