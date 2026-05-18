package com.example.entity.vo.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.hibernate.validator.constraints.Length;

import java.util.List;
import java.util.Map;

/**
 * 创建服务探测任务请求体。
 *
 * <p>{@code type=http} 时可填 {@code expectedStatusCode} / {@code expectedBodyPattern} /
 * {@code headers} / {@code basicAuthUsername} / {@code basicAuthPassword} / {@code sslWarnDays}；
 * 其他类型这些字段将被忽略。
 *
 * <p>敏感字段语义（与 NotificationChannel 同款）：
 * <ul>
 *   <li>{@code headers}：明文 Map，Service 层 JSON 序列化后整体 AES-256-GCM 加密，
 *       未提供（{@code null}）或空 Map 时 {@code headersEnc} 列设为 NULL。</li>
 *   <li>{@code basicAuthPassword}：明文，Service 层加密入库；空字符串视为不启用 Basic Auth。</li>
 * </ul>
 */
@Data
public class ProbeTaskCreateVO {

    @NotBlank
    @Length(max = 128)
    private String name;

    /** 探测类型：http / tcp / icmp。 */
    @NotBlank
    @Pattern(regexp = "http|tcp|icmp", message = "type 仅支持 http / tcp / icmp")
    private String type;

    @NotBlank
    @Length(max = 512)
    private String target;

    @NotNull
    @Min(value = 10, message = "interval_sec 必须 >= 10 秒")
    @Max(value = 86400, message = "interval_sec 必须 <= 86400 秒")
    private Integer intervalSec;

    @NotNull
    @Min(value = 1, message = "timeout_sec 必须 >= 1 秒")
    @Max(value = 60, message = "timeout_sec 必须 <= 60 秒")
    private Integer timeoutSec;

    /** HTTP 期望状态码；为空表示接受 2xx 全段。 */
    @Min(value = 100, message = "expected_status_code 应是合法 HTTP 状态码")
    @Max(value = 599, message = "expected_status_code 应是合法 HTTP 状态码")
    private Integer expectedStatusCode;

    /** HTTP 期望响应体正则；为空表示不校验。 */
    @Length(max = 512)
    private String expectedBodyPattern;

    /** HTTP Custom Headers 明文 Map；为空表示不附加。 */
    private Map<String, String> headers;

    /** Basic Auth 用户名；为空表示不启用 Basic Auth。 */
    @Length(max = 128)
    private String basicAuthUsername;

    /** Basic Auth 密码明文；为空表示不启用。 */
    @Length(max = 255)
    private String basicAuthPassword;

    /** SSL 证书剩余天告警阈值；HTTPS 探测使用，默认 30。 */
    @Min(value = 1)
    @Max(value = 365)
    private Integer sslWarnDays;

    @NotNull
    @Min(value = 1, message = "连续失败阈值必须 >= 1")
    @Max(value = 100)
    private Integer consecutiveFailuresThreshold;

    /** 通知通道 ID 列表；为空表示不发外通知（仅写历史）。 */
    private List<Long> channelIds;

    @NotNull
    private Boolean enabled;
}
