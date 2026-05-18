package com.example.entity.vo.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * v1.3：客户端上报的单个 systemd unit 状态。
 * <p>
 * 由 {@code monitor-client} 的 {@code SystemdCollector} 解析 {@code systemctl show} 输出后，
 * 通过 {@code POST /monitor/systemd} 接口上报至服务端。
 */
@Data
public class SystemdUnitStatVO {
    /**
     * systemd unit 名（例如 nginx.service / sshd@root.service）。
     * <p>
     * 服务端再次校验白名单字符集（防止恶意客户端注入），与客户端 SystemdCollector 同步。
     */
    @NotNull
    @Size(min = 1, max = 128)
    @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._@:\\-]*$",
            message = "unit 名包含非法字符")
    private String name;

    /** LoadState：loaded / not-found / masked / error。 */
    @Size(max = 32)
    private String loadState;

    /** ActiveState：active / inactive / failed / activating / deactivating。 */
    @Size(max = 32)
    private String activeState;

    /** SubState：running / dead / failed / exited 等。 */
    @Size(max = 32)
    private String subState;

    /** Description 字段。 */
    @Size(max = 256)
    private String description;

    /** unit 健康标志：activeState=="active" && subState=="running" 时为 true。 */
    private boolean healthy;
}
