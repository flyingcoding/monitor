package org.monitorclient.collector;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.monitorclient.entity.Capabilities;
import org.monitorclient.entity.RuntimeDetail;
import org.monitorclient.system.CommandExecutor;
import org.monitorclient.system.MetricCollector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * v1.3 SMART 磁盘健康采集器。
 * <p>
 * 通过 {@code smartctl -A -j /dev/<device>} 解析每个配置设备的关键属性，将关键异常数聚合为
 * {@link RuntimeDetail#getSmartCriticalCount()} 上报；同时维护一份线程安全的最新快照，
 * 供独立上报接口（{@code /monitor/smart}）使用。
 *
 * <h3>关键属性判定</h3>
 * <ul>
 *   <li>SATA：{@code ata_smart_attributes.table[]} 中 id=5 (reallocated)、id=197 (pending)、
 *       id=198 (uncorrectable)、id=194 (temperature) 的 {@code raw.value}；</li>
 *   <li>NVMe：{@code nvme_smart_health_information_log.{critical_warning, media_errors, temperature}}，
 *       温度单位为 Kelvin 需要 -273 转 Celsius。</li>
 * </ul>
 * <h3>失败安全</h3>
 * <ul>
 *   <li>{@code smartctl} 缺失 → 能力 {@code available=false}，不参与采集；</li>
 *   <li>配置 devices 为空 → 能力 {@code enabled=false}；</li>
 *   <li>单设备执行失败 / JSON 解析失败 → 跳过该 device，不算 critical，不抛异常；</li>
 *   <li>单次 smartctl 执行预算 5 秒。</li>
 * </ul>
 * <h3>设备路径校验</h3>
 * 仅接受 {@code /dev/sd*}、{@code /dev/nvme*n*}、{@code /dev/hd*} 前缀，避免命令注入。
 */
@Slf4j
public class SmartCollector implements MetricCollector {

    /** 单次 smartctl 执行超时。 */
    private static final Duration SMARTCTL_TIMEOUT = Duration.ofSeconds(5);
    /** 设备路径正则白名单（防注入）。 */
    private static final Pattern DEVICE_PATTERN = Pattern.compile("^/dev/(sd[a-z]+|nvme\\d+n\\d+|hd[a-z]+)$");

    /** SMART 属性 ID：reallocated_sector_count。 */
    private static final int ID_REALLOCATED = 5;
    /** SMART 属性 ID：temperature_celsius。 */
    private static final int ID_TEMPERATURE = 194;
    /** SMART 属性 ID：current_pending_sector。 */
    private static final int ID_CURRENT_PENDING = 197;
    /** SMART 属性 ID：offline_uncorrectable。 */
    private static final int ID_OFFLINE_UNCORRECTABLE = 198;

    private final CommandExecutor executor;
    /** 通过 application.properties 配置的合法设备列表。 */
    private final List<String> devices;
    /** smartctl 是否可用。 */
    private final boolean smartctlAvailable;
    /** 模块是否启用（devices 非空）。 */
    private final boolean enabled;
    /** smartctl 不可用时的原因。 */
    private final String unavailableReason;
    /** 最新快照（thread-safe），供独立上报接口取用。 */
    private final AtomicReference<List<SmartStat>> lastSnapshot = new AtomicReference<>(Collections.emptyList());

    /**
     * 构造 SmartCollector，从 application.properties 读取 {@code monitor.collect.smart.devices}。
     *
     * @param executor 命令执行器
     * @param config   客户端配置
     */
    public SmartCollector(CommandExecutor executor, Properties config) {
        this.executor = executor;
        this.devices = parseDevices(config);
        this.enabled = !devices.isEmpty();
        if (!this.enabled) {
            this.smartctlAvailable = false;
            this.unavailableReason = "monitor.collect.smart.devices 未配置";
        } else {
            boolean available = false;
            String reason = null;
            try {
                available = executor.isAvailable("smartctl");
                if (!available) {
                    reason = "smartctl 未检测到";
                }
            } catch (Exception e) {
                reason = "smartctl 探测异常：" + e.getMessage();
            }
            this.smartctlAvailable = available;
            this.unavailableReason = reason;
        }
        log.info("SmartCollector 初始化完成 enabled={}, smartctlAvailable={}, devices={}",
                enabled, smartctlAvailable, devices);
    }

    /**
     * 解析配置中的 devices，过滤非法路径并去重保留顺序。
     *
     * @param config 配置
     * @return 合法设备路径列表
     */
    private List<String> parseDevices(Properties config) {
        String raw = config == null ? null : config.getProperty("monitor.collect.smart.devices", "");
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String token : Arrays.asList(raw.split(","))) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            if (!DEVICE_PATTERN.matcher(trimmed).matches()) {
                log.warn("忽略非法 SMART 设备路径：{}", trimmed);
                continue;
            }
            if (!result.contains(trimmed)) {
                result.add(trimmed);
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public String name() {
        return "smart";
    }

    @Override
    public Capabilities.Module describe() {
        Capabilities.Module module = new Capabilities.Module()
                .setEnabled(enabled)
                .setAvailable(smartctlAvailable)
                .setItems(devices)
                .setCount(devices.size());
        if (unavailableReason != null) {
            module.setUnavailableReason(unavailableReason);
        }
        return module;
    }

    @Override
    public void enhance(RuntimeDetail runtime) {
        if (!enabled || !smartctlAvailable) {
            return;
        }
        List<SmartStat> snapshot = new ArrayList<>(devices.size());
        int criticalCount = 0;
        for (String device : devices) {
            SmartStat stat = collectDevice(device);
            if (stat == null) {
                continue;
            }
            snapshot.add(stat);
            if (stat.isCritical()) {
                criticalCount++;
            }
        }
        lastSnapshot.set(Collections.unmodifiableList(snapshot));
        runtime.setSmartCriticalCount(criticalCount);
    }

    /**
     * 返回最新一次 enhance 后的快照，供独立上报接口使用。
     *
     * @return 不可变快照列表（可能为空集合，但不为 null）
     */
    public List<SmartStat> lastSnapshot() {
        return lastSnapshot.get();
    }

    /**
     * 采集单个设备的 SMART 数据，命令失败 / 解析异常时返回 null（不算 critical）。
     *
     * @param device 设备路径
     * @return 单磁盘快照，失败返回 null
     */
    private SmartStat collectDevice(String device) {
        try {
            CommandExecutor.CommandResult result = executor.execute(
                    List.of("smartctl", "-A", "-j", device), SMARTCTL_TIMEOUT);
            // smartctl exit code 非 0 但仍可能输出有效 JSON（如某些 ATA 错误位），优先尝试解析 stdout
            if (result.timedOut()) {
                log.warn("smartctl 执行超时 device={}", device);
                return null;
            }
            String stdout = result.stdout();
            if (stdout == null || stdout.isBlank()) {
                log.warn("smartctl 无输出 device={}, exitCode={}, stderr={}",
                        device, result.exitCode(), result.stderr());
                return null;
            }
            JSONObject json = JSON.parseObject(stdout);
            if (json == null) {
                log.warn("smartctl 输出非 JSON device={}", device);
                return null;
            }
            return parseSmartJson(device, json);
        } catch (Exception e) {
            log.warn("解析 SMART 数据失败 device={}, reason={}", device, e.getMessage());
            return null;
        }
    }

    /**
     * 从 {@code smartctl -A -j} 的 JSON 输出解析单磁盘指标。
     *
     * @param device 设备路径
     * @param json   smartctl JSON
     * @return 单磁盘快照
     */
    private SmartStat parseSmartJson(String device, JSONObject json) {
        SmartStat stat = new SmartStat().setDevice(device);
        stat.setModelName(json.getString("model_name"));
        JSONObject nvmeLog = json.getJSONObject("nvme_smart_health_information_log");
        if (nvmeLog != null) {
            stat.setNvme(true);
            Long mediaErrors = readLong(nvmeLog, "media_errors");
            stat.setMediaErrors(mediaErrors);
            Long criticalWarning = readLong(nvmeLog, "critical_warning");
            Integer temperatureKelvin = readInt(nvmeLog, "temperature");
            if (temperatureKelvin != null) {
                stat.setTemperatureCelsius(temperatureKelvin - 273);
            }
            stat.setCritical(positive(criticalWarning) || positive(mediaErrors));
            return stat;
        }
        JSONObject ataAttrs = json.getJSONObject("ata_smart_attributes");
        if (ataAttrs != null) {
            JSONArray table = ataAttrs.getJSONArray("table");
            if (table != null) {
                for (int i = 0; i < table.size(); i++) {
                    JSONObject row = table.getJSONObject(i);
                    if (row == null) continue;
                    Integer id = row.getInteger("id");
                    if (id == null) continue;
                    Long rawValue = readRawValue(row);
                    if (id == ID_REALLOCATED) {
                        stat.setReallocatedSector(rawValue);
                    } else if (id == ID_TEMPERATURE) {
                        if (rawValue != null) {
                            stat.setTemperatureCelsius(rawValue.intValue());
                        }
                    } else if (id == ID_CURRENT_PENDING) {
                        stat.setCurrentPending(rawValue);
                    } else if (id == ID_OFFLINE_UNCORRECTABLE) {
                        stat.setOfflineUncorrectable(rawValue);
                    }
                }
            }
        }
        boolean critical = positive(stat.getReallocatedSector())
                || positive(stat.getCurrentPending())
                || positive(stat.getOfflineUncorrectable());
        stat.setCritical(critical);
        return stat;
    }

    /**
     * 读取 ATA 表行的 {@code raw.value}，回退到 {@code raw_value}。
     *
     * @param row JSON 行
     * @return 原始值或 null
     */
    private Long readRawValue(JSONObject row) {
        JSONObject raw = row.getJSONObject("raw");
        if (raw != null) {
            Long v = readLong(raw, "value");
            if (v != null) return v;
        }
        return readLong(row, "raw_value");
    }

    /**
     * 安全读取 JSON Long 字段（兼容 number / string）。
     *
     * @param obj JSON 对象
     * @param key 字段名
     * @return Long 或 null
     */
    private Long readLong(JSONObject obj, String key) {
        try {
            return obj.getLong(key);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 安全读取 JSON Integer 字段。
     *
     * @param obj JSON 对象
     * @param key 字段名
     * @return Integer 或 null
     */
    private Integer readInt(JSONObject obj, String key) {
        try {
            return obj.getInteger(key);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 判断长整型是否大于 0（null 视为 0）。
     *
     * @param value 值
     * @return 大于 0 返回 true
     */
    private boolean positive(Long value) {
        return value != null && value > 0L;
    }
}
