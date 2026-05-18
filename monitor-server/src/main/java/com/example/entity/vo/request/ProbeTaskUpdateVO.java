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
 * 更新服务探测任务请求体。
 *
 * <p>{@code basicAuthPassword} 与 {@code headers} 的"留空保留旧密文"语义：
 * <ul>
 *   <li>{@code headers} 为 {@code null}：保留旧 {@code headersEnc}；</li>
 *   <li>{@code headers} 为空 Map：清空 {@code headersEnc}（列设为 NULL）；</li>
 *   <li>{@code headers} 含 {@link com.example.service.impl.ProbeServiceImpl#MASK_PLACEHOLDER} 占位：
 *       保留旧密文（前端为避免回显敏感值发回 {@code "***"}）；</li>
 *   <li>{@code basicAuthPassword} 为 {@code null} 或等于 {@code "***"}：沿用旧密文；</li>
 *   <li>{@code basicAuthPassword} 为空字符串：清空（列设为 NULL）。</li>
 * </ul>
 */
@Data
public class ProbeTaskUpdateVO {

    @NotBlank
    @Length(max = 128)
    private String name;

    @NotBlank
    @Pattern(regexp = "http|tcp|icmp")
    private String type;

    @NotBlank
    @Length(max = 512)
    private String target;

    @NotNull
    @Min(value = 10)
    @Max(value = 86400)
    private Integer intervalSec;

    @NotNull
    @Min(value = 1)
    @Max(value = 60)
    private Integer timeoutSec;

    @Min(value = 100)
    @Max(value = 599)
    private Integer expectedStatusCode;

    @Length(max = 512)
    private String expectedBodyPattern;

    /**
     * HTTP Custom Headers：
     * <ul>
     *   <li>{@code null} 表示沿用旧密文，</li>
     *   <li>空 Map 表示清空，</li>
     *   <li>含具体值表示替换。</li>
     * </ul>
     */
    private Map<String, String> headers;

    @Length(max = 128)
    private String basicAuthUsername;

    /** Basic Auth 密码明文；{@code null} 或 {@code "***"} 表示沿用旧密文，空字符串表示清空。 */
    @Length(max = 255)
    private String basicAuthPassword;

    @Min(value = 1)
    @Max(value = 365)
    private Integer sslWarnDays;

    @NotNull
    @Min(value = 1)
    @Max(value = 100)
    private Integer consecutiveFailuresThreshold;

    private List<Long> channelIds;

    @NotNull
    private Boolean enabled;
}
