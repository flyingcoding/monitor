package com.example.controller.otlp;

import com.example.entity.vo.request.RuntimeDetailVO;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * OTLP {@code ExportMetricsServiceRequest} → {@link RuntimeDetailVO} 白名单映射器 (v2.0-alpha)。
 *
 * <p>仅识别 {@code monitor.client.*} 命名空间下的 Gauge 类型指标（决策 D3）；
 * 单位约定与 {@link RuntimeDetailVO} 1:1，调用方（OTel Collector 端）负责通过 {@code transform}
 * processor 把 {@code system.*} 标准名改写为 {@code monitor.client.*}。
 *
 * <h3>白名单</h3>
 * <ul>
 *   <li>{@code monitor.client.cpu_usage} (0~1) → {@code cpuUsage}</li>
 *   <li>{@code monitor.client.memory_used_gb} → {@code memoryUsage}</li>
 *   <li>{@code monitor.client.disk_used_gb} → {@code diskUsage}</li>
 *   <li>{@code monitor.client.network_upload_kbps} → {@code networkUpload}</li>
 *   <li>{@code monitor.client.network_download_kbps} → {@code networkDownload}</li>
 *   <li>{@code monitor.client.disk_read_mbps} → {@code diskRead}</li>
 *   <li>{@code monitor.client.disk_write_mbps} → {@code diskWrite}</li>
 *   <li>{@code monitor.client.gpu_temperature_max} → {@code gpuTemperatureMax}</li>
 *   <li>{@code monitor.client.smart_critical_count} → {@code smartCriticalCount}</li>
 *   <li>{@code monitor.client.systemd_failed_count} → {@code systemdFailedCount}</li>
 *   <li>{@code monitor.client.watched_process_missing} → {@code watchedProcessMissing}</li>
 * </ul>
 *
 * <p>未识别 metric 名称累加进 {@link Result#unknownMetricCounts}；resource 中的 {@code host.name}
 * 取出到 {@link Result#hostName} 供调用方与 token 绑定 client 交叉校验（决策 D2）。
 */
@Slf4j
public final class OtlpMetricParser {

    /** 白名单 metric 命名空间前缀（约定）。 */
    public static final String NAMESPACE = "monitor.client.";

    /** 能安全写入 {@link RuntimeDetailVO} 的基础运行时 metric 集合。 */
    public static final Set<String> BASE_RUNTIME_METRICS = Set.of(
            NAMESPACE + "cpu_usage",
            NAMESPACE + "memory_used_gb",
            NAMESPACE + "disk_used_gb",
            NAMESPACE + "network_upload_kbps",
            NAMESPACE + "network_download_kbps",
            NAMESPACE + "disk_read_mbps",
            NAMESPACE + "disk_write_mbps"
    );

    /** OTel 标准 resource attribute key：主机名。 */
    public static final String RESOURCE_HOST_NAME = "host.name";

    private static final Map<String, BiConsumer<RuntimeDetailVO, Double>> WHITELIST = new HashMap<>();

    static {
        WHITELIST.put(NAMESPACE + "cpu_usage", (vo, v) -> vo.setCpuUsage(v));
        WHITELIST.put(NAMESPACE + "memory_used_gb", (vo, v) -> vo.setMemoryUsage(v));
        WHITELIST.put(NAMESPACE + "disk_used_gb", (vo, v) -> vo.setDiskUsage(v));
        WHITELIST.put(NAMESPACE + "network_upload_kbps", (vo, v) -> vo.setNetworkUpload(v));
        WHITELIST.put(NAMESPACE + "network_download_kbps", (vo, v) -> vo.setNetworkDownload(v));
        WHITELIST.put(NAMESPACE + "disk_read_mbps", (vo, v) -> vo.setDiskRead(v));
        WHITELIST.put(NAMESPACE + "disk_write_mbps", (vo, v) -> vo.setDiskWrite(v));
        WHITELIST.put(NAMESPACE + "gpu_temperature_max", (vo, v) -> vo.setGpuTemperatureMax(v));
        WHITELIST.put(NAMESPACE + "smart_critical_count", (vo, v) -> vo.setSmartCriticalCount(v.intValue()));
        WHITELIST.put(NAMESPACE + "systemd_failed_count", (vo, v) -> vo.setSystemdFailedCount(v.intValue()));
        WHITELIST.put(NAMESPACE + "watched_process_missing", (vo, v) -> vo.setWatchedProcessMissing(v.intValue()));
    }

    private OtlpMetricParser() {
        // 工具类，禁止实例化
    }

    /**
     * 解析 OTLP 请求为映射后的 VO 列表。
     *
     * <p>一个请求可能包含多个 {@link ResourceMetrics}（不同 host），每个 ResourceMetrics 映射成一个
     * {@link Result}。同一 ResourceMetrics 内的多个 metric 合并到同一 VO，时间戳取最新数据点。
     *
     * @param request OTLP 请求（从 Protobuf bytes 或 JSON 解析得到）
     * @return 每个 ResourceMetrics 对应一个结果项，可能为空列表
     */
    public static List<Result> parse(ExportMetricsServiceRequest request) {
        List<Result> results = new ArrayList<>();
        if (request == null) {
            return results;
        }
        for (ResourceMetrics rm : request.getResourceMetricsList()) {
            Result result = parseResource(rm);
            if (result != null) {
                results.add(result);
            }
        }
        return results;
    }

    /**
     * 解析单个 ResourceMetrics：取 host.name + 聚合 Gauge metric。
     *
     * @param rm ResourceMetrics
     * @return 解析结果；若该 host 没有任何白名单 metric 也没有未知 metric 则返回 null
     */
    private static Result parseResource(ResourceMetrics rm) {
        Result result = new Result();
        result.hostName = extractHostName(rm);
        boolean hasAnyMetric = false;
        for (ScopeMetrics sm : rm.getScopeMetricsList()) {
            for (Metric metric : sm.getMetricsList()) {
                String name = metric.getName();
                BiConsumer<RuntimeDetailVO, Double> setter = WHITELIST.get(name);
                if (setter == null) {
                    result.unknownMetricCounts.merge(name, 1L, Long::sum);
                    continue;
                }
                Optional<NumberDataPoint> last = latestGaugePoint(metric);
                if (last.isEmpty()) {
                    log.warn("OTLP metric {} 非 Gauge、无数据点或数据点未携带数值，跳过", name);
                    continue;
                }
                NumberDataPoint dp = last.get();
                Optional<Double> value = numericValue(dp);
                if (value.isEmpty()) {
                    log.warn("OTLP metric {} 数据点未携带数值，跳过", name);
                    continue;
                }
                setter.accept(result.runtime, value.get());
                result.mappedMetricNames.add(name);
                long ts = dp.getTimeUnixNano() / 1_000_000L;
                if (ts > result.runtime.getTimestamp()) {
                    result.runtime.setTimestamp(ts);
                }
                hasAnyMetric = true;
            }
        }
        if (!hasAnyMetric && result.unknownMetricCounts.isEmpty()) {
            return null;
        }
        if (result.runtime.getTimestamp() == 0) {
            result.runtime.setTimestamp(System.currentTimeMillis());
        }
        return result;
    }

    /**
     * 提取 OTel 标准 resource attribute {@code host.name}。
     */
    private static String extractHostName(ResourceMetrics rm) {
        if (!rm.hasResource()) {
            return null;
        }
        for (KeyValue kv : rm.getResource().getAttributesList()) {
            if (RESOURCE_HOST_NAME.equals(kv.getKey()) && kv.getValue().hasStringValue()) {
                return kv.getValue().getStringValue();
            }
        }
        return null;
    }

    /**
     * 取 Gauge 类型 metric 中时间戳最大的 NumberDataPoint。
     *
     * <p>v2.0-alpha 仅支持 Gauge；Sum / Histogram 类型直接跳过（决策 D3：不在服务端做 cumulative 计算）。
     */
    private static Optional<NumberDataPoint> latestGaugePoint(Metric metric) {
        if (!metric.hasGauge()) {
            return Optional.empty();
        }
        return metric.getGauge().getDataPointsList().stream()
                .filter(OtlpMetricParser::hasNumericValue)
                .reduce((a, b) -> a.getTimeUnixNano() >= b.getTimeUnixNano() ? a : b);
    }

    /**
     * 判断数据点是否携带 OTLP 数值字段。
     */
    private static boolean hasNumericValue(NumberDataPoint dp) {
        return dp.getValueCase() == NumberDataPoint.ValueCase.AS_DOUBLE
                || dp.getValueCase() == NumberDataPoint.ValueCase.AS_INT;
    }

    /**
     * 把 NumberDataPoint 的 int / double 值统一转 double；缺失数值时返回空。
     */
    private static Optional<Double> numericValue(NumberDataPoint dp) {
        return switch (dp.getValueCase()) {
            case AS_DOUBLE -> Optional.of(dp.getAsDouble());
            case AS_INT -> Optional.of((double) dp.getAsInt());
            default -> Optional.empty();
        };
    }

    /**
     * 单个 ResourceMetrics 的解析结果。
     */
    @Data
    public static final class Result {
        /** 映射后的运行时 VO；调用方调用 ClientService.updateRuntimeDetail 时复用此对象。 */
        private final RuntimeDetailVO runtime = new RuntimeDetailVO();

        /** OTel resource attribute {@code host.name}；为空时调用方跳过交叉校验。 */
        private String hostName;

        /** 该 ResourceMetrics 中未识别 metric 的计数；key=metric 名称，value=出现次数。 */
        private final Map<String, Long> unknownMetricCounts = new HashMap<>();

        /** 已成功映射到 {@link RuntimeDetailVO} 的 metric 名称集合。 */
        private final Set<String> mappedMetricNames = new HashSet<>();

        /**
         * 判断基础 7 项运行时 metric 是否齐全。
         *
         * @return true 表示可以安全写入 runtime measurement；false 表示写入会把缺失字段误置为 0
         */
        public boolean hasCompleteBaseMetrics() {
            return mappedMetricNames.containsAll(BASE_RUNTIME_METRICS);
        }

        /**
         * 返回缺失的基础 metric 名称，便于日志定位 Collector 转换配置问题。
         *
         * @return 缺失的基础 metric 名称集合
         */
        public Set<String> missingBaseMetricNames() {
            Set<String> missing = new HashSet<>(BASE_RUNTIME_METRICS);
            missing.removeAll(mappedMetricNames);
            return missing;
        }
    }
}
