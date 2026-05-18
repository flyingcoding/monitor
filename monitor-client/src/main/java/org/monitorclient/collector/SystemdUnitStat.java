package org.monitorclient.collector;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 单个 systemd unit 的状态快照。
 * <p>
 * 由 {@link SystemdCollector} 每周期采集并通过 {@code /monitor/systemd} 上报至服务端。
 * {@link #healthy} 字段由 {@code activeState=="active" && subState=="running"} 计算得出，
 * 服务端聚合 {@code !healthy} 的 unit 数作为 {@code systemdFailedCount} 告警 metric。
 */
@Data
@Accessors(chain = true)
public class SystemdUnitStat {
    /** systemd unit 名（例如 nginx.service / sshd@root.service）。 */
    private String name;
    /** LoadState：loaded / not-found / masked / error。 */
    private String loadState;
    /** ActiveState：active / inactive / failed / activating / deactivating。 */
    private String activeState;
    /** SubState：running / dead / failed / exited 等。 */
    private String subState;
    /** Description 字段（如 "The PHP FastCGI Process Manager"）。 */
    private String description;
    /** unit 健康标志：activeState=="active" && subState=="running" 时为 true。 */
    private boolean healthy;
}
