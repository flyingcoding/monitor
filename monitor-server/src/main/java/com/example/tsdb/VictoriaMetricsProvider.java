package com.example.tsdb;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.entity.dto.RuntimeData;
import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.entity.vo.response.RuntimeHistoryVO;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@link TimeSeriesAdapter} 的 VictoriaMetrics 实现（v2.0-beta 落地）。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li><b>写入零依赖增量</b>：VictoriaMetrics 提供 InfluxDB v2 line protocol 兼容端点
 *       （{@code POST /api/v2/write}），可直接复用 {@code influxdb-client-java}。
 *       VM 忽略 org / bucket / token，但 SDK 要求非空，使用 {@code monitor} / {@code _} 占位。</li>
 *   <li><b>断路器共享</b>：与 {@link InfluxDbProvider} 共用 {@code @CircuitBreaker(name="tsdb")}，
 *       同一份断路器配置可控制两种 provider 的 fallback 节奏；fallback 走
 *       {@link InfluxDbProvider#writeRuntime} 的同款 JSONL 缓冲（PR1 已重命名为 tsdb-buffer）。</li>
 *   <li><b>JSONL 缓冲复用</b>：直接注入 {@link InfluxDbProvider} 作为 fallback 写入目标。
 *       这避免重写一份 buffer 路径，并保证切换 provider 时旧缓冲可被重放。</li>
 *   <li><b>查询路径</b>：用 Spring {@link RestClient} 调 VM 的 PromQL 端点 {@code /api/v1/query_range}：
 *       <ul>
 *         <li>{@link #readRuntimeHistory}：查 1h 内所有 {@code runtime_*} metric，按时间戳合并为 VO 列表；</li>
 *         <li>{@link #readAvailabilityBuckets}：用 MetricsQL 扩展函数 {@code present_over_time}
 *             返回 48 个 0/1 桶，对应 24h × 30min。</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <h3>Metric 命名（与 vmctl 历史一致）</h3>
 * <p>沿用 line protocol 写入，VM 内部会把 {@code measurement=runtime, field=cpuUsage} 展平为
 * {@code runtime_cpuUsage}，与 vmctl 默认迁移命名一致；OTLP / client 直传 / 历史数据三者天然合流。
 * 查询返回时 strip {@code runtime_} 前缀以匹配 {@link InfluxDbProvider#readRuntimeHistory} 的字段名约定，
 * 让前端图表无需感知 provider。
 */
@Slf4j
@Component
public class VictoriaMetricsProvider implements TimeSeriesAdapter {

    /** VM 兼容 InfluxDB SDK 调用所需的占位 organization。VM 实际忽略此值。 */
    public static final String VM_PLACEHOLDER_ORG = "monitor";

    /** VM 兼容 InfluxDB SDK 调用所需的占位 bucket / token。VM 实际忽略此值。 */
    public static final String VM_PLACEHOLDER_TOKEN_OR_BUCKET = "_";

    /** VM 写入 measurement 名称，与 InfluxDB 保持一致以让历史曲线 / vmctl 迁移结果命名统一。 */
    public static final String MEASUREMENT_RUNTIME = "runtime";

    /** line protocol 写入后 VM 内 metric 名前缀（{@code <measurement>_<field>}），查询返回时需 strip。 */
    public static final String METRIC_NAME_PREFIX = MEASUREMENT_RUNTIME + "_";

    /** PromQL query_range 端点。 */
    public static final String QUERY_RANGE_PATH = "/api/v1/query_range";

    /** 1h 历史曲线的采样间隔。 */
    public static final int RUNTIME_HISTORY_STEP_SECONDS = 10;

    /** 24h 可用率桶的步长（30 分钟），与 {@link InfluxDbProvider#BUCKET_MINUTES} 对齐。 */
    public static final int AVAILABILITY_STEP_SECONDS = InfluxDbProvider.BUCKET_MINUTES * 60;

    @Value("${monitor.tsdb.victoria-metrics.url:http://victoria-metrics:8428}")
    private String url;

    @Value("${monitor.tsdb.victoria-metrics.query-timeout-ms:5000}")
    private int queryTimeoutMs;

    /** PR1 引入的同 Bean，VM 写入失败降级时调用其 buffer 路径，复用 JSONL 缓冲 + 重放。 */
    private final InfluxDbProvider fallbackBuffer;

    private InfluxDBClient writeClient;
    private WriteApiBlocking writeApi;
    private RestClient queryClient;

    /**
     * @param fallbackBuffer 始终存在的 {@link InfluxDbProvider} Bean（spec 强约束：必须无条件注册），
     *                       作为 VM 写入降级的 JSONL 缓冲承载方
     */
    public VictoriaMetricsProvider(InfluxDbProvider fallbackBuffer) {
        this.fallbackBuffer = fallbackBuffer;
    }

    /**
     * 初始化 VM 兼容 InfluxDB SDK 写入客户端 + PromQL 查询用 RestClient。
     * VM 接受任何非空 token / org / bucket，占位常量见 {@link #VM_PLACEHOLDER_ORG} /
     * {@link #VM_PLACEHOLDER_TOKEN_OR_BUCKET}。
     */
    @PostConstruct
    public void init() {
        writeClient = InfluxDBClientFactory.create(
                url,
                VM_PLACEHOLDER_TOKEN_OR_BUCKET.toCharArray(),
                VM_PLACEHOLDER_ORG,
                VM_PLACEHOLDER_TOKEN_OR_BUCKET);
        writeApi = writeClient.getWriteApiBlocking();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(queryTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(queryTimeoutMs));
        queryClient = RestClient.builder()
                .baseUrl(url)
                .requestFactory(factory)
                .build();

        log.info("VictoriaMetricsProvider 已初始化，写入端点={}/api/v2/write，查询端点={}{}（超时 {}ms）",
                url, url, QUERY_RANGE_PATH, queryTimeoutMs);
    }

    /**
     * 关闭 VM 写入客户端资源。
     */
    @PreDestroy
    public void close() {
        if (writeClient != null) {
            writeClient.close();
        }
    }

    @Override
    @CircuitBreaker(name = "tsdb", fallbackMethod = "writeToFallbackBuffer")
    public void writeRuntime(int clientId, RuntimeDetailVO vo) {
        this.doWriteRuntimeData(clientId, vo);
    }

    @Override
    @CircuitBreaker(name = "tsdb", fallbackMethod = "writeToFallbackBuffer")
    public void writeOtlpMetric(int clientId, RuntimeDetailVO vo) {
        this.doWriteRuntimeData(clientId, vo);
    }

    /**
     * 断路器回退逻辑：VM 写入失败时把数据交给共享 JSONL 缓冲（{@link InfluxDbProvider}），
     * 由 {@link InfluxDbProvider#replayBufferedData} 后台调度重放——重放时仍走当前活跃 provider，
     * 也就是 VM 自身；这样确保 v1.x 缓冲与 v2.0-beta 缓冲共享同一份"未传送"队列。
     *
     * @param clientId  客户端 ID
     * @param vo        运行时数据
     * @param throwable 触发回退的异常
     */
    private void writeToFallbackBuffer(int clientId, RuntimeDetailVO vo, Throwable throwable) {
        log.warn("VictoriaMetrics 写入降级到本地缓冲，clientId={}, reason={}", clientId,
                throwable == null ? "unknown" : throwable.getMessage());
        // 直接调用 buffer 写入路径；InfluxDbProvider.writeRuntime 自身也会走 @CircuitBreaker，
        // 当 VM 与 Influx 同时不可用时，会重入 InfluxDbProvider 的 writeToFileBuffer 走 JSONL。
        // 这里通过 @CircuitBreaker 隔离的两层 fallback 形成"VM → Influx → JSONL"的级联降级。
        fallbackBuffer.writeRuntime(clientId, vo);
    }

    /**
     * 把 {@link RuntimeDetailVO} 转换为 {@link RuntimeData} 并通过 line protocol 写入 VM。
     *
     * @param clientId 客户端 ID
     * @param vo       运行时数据
     */
    private void doWriteRuntimeData(int clientId, RuntimeDetailVO vo) {
        RuntimeData data = new RuntimeData();
        BeanUtils.copyProperties(vo, data);
        data.setClientId(clientId);
        data.setTimestamp(new Date(vo.getTimestamp()).toInstant());
        // VM 忽略 bucket / org，但 SDK 必填，传占位常量
        writeApi.writeMeasurement(
                VM_PLACEHOLDER_TOKEN_OR_BUCKET,
                VM_PLACEHOLDER_ORG,
                WritePrecision.NS,
                data);
    }

    @Override
    public RuntimeHistoryVO readRuntimeHistory(int clientId) {
        RuntimeHistoryVO vo = new RuntimeHistoryVO();
        Instant end = Instant.now();
        Instant start = end.minusSeconds(3600);
        String query = String.format("{__name__=~\"%s.*\", clientId=\"%d\"}", METRIC_NAME_PREFIX, clientId);
        JSONObject response;
        try {
            response = this.queryRange(query, start, end, RUNTIME_HISTORY_STEP_SECONDS);
        } catch (RestClientException e) {
            log.warn("VictoriaMetrics readRuntimeHistory 查询失败 clientId={}: {}", clientId, e.getMessage());
            throw e;
        }
        JSONArray result = this.extractMatrixResult(response);
        if (result == null || result.isEmpty()) {
            return vo;
        }

        // 按时间戳聚合：TreeMap 保证按时间戳升序输出，与 InfluxDB 表现一致
        Map<Long, JSONObject> byTimestamp = new TreeMap<>();
        for (int i = 0; i < result.size(); i++) {
            JSONObject series = result.getJSONObject(i);
            String metricName = series.getJSONObject("metric").getString("__name__");
            if (metricName == null || !metricName.startsWith(METRIC_NAME_PREFIX)) {
                continue;
            }
            String fieldName = metricName.substring(METRIC_NAME_PREFIX.length());
            JSONArray values = series.getJSONArray("values");
            if (values == null) {
                continue;
            }
            for (int j = 0; j < values.size(); j++) {
                JSONArray point = values.getJSONArray(j);
                if (point == null || point.size() < 2) {
                    continue;
                }
                long tsMillis = secondsToMillis(point.getDouble(0));
                Object rawValue = parsePromValue(point.getString(1));
                JSONObject row = byTimestamp.computeIfAbsent(tsMillis, ts -> {
                    JSONObject obj = new JSONObject();
                    obj.put("timestamp", Instant.ofEpochMilli(ts));
                    return obj;
                });
                row.put(fieldName, rawValue);
            }
        }
        vo.getList().addAll(byTimestamp.values());
        return vo;
    }

    @Override
    public double[] readAvailabilityBuckets(int clientId) {
        Instant end = Instant.now();
        Instant start = end.minusSeconds((long) InfluxDbProvider.AVAILABILITY_WINDOW_HOURS * 3600);
        String query = String.format(
                "present_over_time(%scpuUsage{clientId=\"%d\"}[%dm])",
                METRIC_NAME_PREFIX, clientId, InfluxDbProvider.BUCKET_MINUTES);
        JSONObject response;
        try {
            response = this.queryRange(query, start, end, AVAILABILITY_STEP_SECONDS);
        } catch (RestClientException e) {
            log.warn("VictoriaMetrics readAvailabilityBuckets 查询失败 clientId={}: {}", clientId, e.getMessage());
            throw e;
        }
        JSONArray result = this.extractMatrixResult(response);
        if (result == null || result.isEmpty()) {
            return new double[0];
        }
        JSONArray values = result.getJSONObject(0).getJSONArray("values");
        if (values == null || values.isEmpty()) {
            return new double[0];
        }
        List<Double> sampled = new ArrayList<>(InfluxDbProvider.BUCKET_COUNT_24H);
        for (int i = 0; i < values.size(); i++) {
            JSONArray point = values.getJSONArray(i);
            if (point == null || point.size() < 2) {
                sampled.add(0.0);
                continue;
            }
            String raw = point.getString(1);
            double v = "NaN".equalsIgnoreCase(raw) ? 0.0 : Double.parseDouble(raw);
            sampled.add(v > 0 ? 1.0 : 0.0);
        }
        // 与 InfluxDbProvider 的对齐策略一致：按 BUCKET_COUNT_24H 截断或右对齐
        double[] buckets = new double[InfluxDbProvider.BUCKET_COUNT_24H];
        int srcStart = Math.max(0, sampled.size() - InfluxDbProvider.BUCKET_COUNT_24H);
        int dstOffset = InfluxDbProvider.BUCKET_COUNT_24H - (sampled.size() - srcStart);
        for (int i = srcStart; i < sampled.size(); i++) {
            buckets[dstOffset + (i - srcStart)] = sampled.get(i);
        }
        return buckets;
    }

    /**
     * 调 VM PromQL {@code /api/v1/query_range} 并解析响应。返回原始 JSON，调用方按 resultType 处理。
     *
     * @param query   PromQL 表达式
     * @param start   起始时间
     * @param end     结束时间
     * @param stepSec 步长（秒）
     * @return Prometheus 标准响应 JSON
     */
    private JSONObject queryRange(String query, Instant start, Instant end, int stepSec) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("query", query);
        form.add("start", String.valueOf(start.getEpochSecond()));
        form.add("end", String.valueOf(end.getEpochSecond()));
        form.add("step", stepSec + "s");
        String body = queryClient.post()
                .uri(QUERY_RANGE_PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
        if (body == null || body.isBlank()) {
            return new JSONObject();
        }
        return JSON.parseObject(body);
    }

    /**
     * 校验响应并取出 {@code data.result} 数组。响应非 success 或缺字段时返回 null。
     *
     * @param response /api/v1/query_range 响应 JSON
     * @return matrix 结果数组，可能为 null
     */
    private JSONArray extractMatrixResult(JSONObject response) {
        if (response == null) {
            return null;
        }
        String status = response.getString("status");
        if (!"success".equalsIgnoreCase(status)) {
            log.warn("VictoriaMetrics 查询响应非 success：status={} errorType={} error={}",
                    status, response.getString("errorType"), response.getString("error"));
            return null;
        }
        JSONObject data = response.getJSONObject("data");
        if (data == null) {
            return null;
        }
        return data.getJSONArray("result");
    }

    /**
     * Prometheus 返回的样本值是字符串数字（含 NaN / Inf）；解析为 Double 或保留原文。
     *
     * @param raw 原始字符串
     * @return Double 或原始 String（无法解析时）
     */
    private static Object parsePromValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if ("NaN".equalsIgnoreCase(raw) || "+Inf".equalsIgnoreCase(raw) || "-Inf".equalsIgnoreCase(raw)) {
            return raw;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    /**
     * Prometheus 时间戳为浮点秒（含小数毫秒）；转 epoch millis。
     *
     * @param epochSeconds 浮点秒时间戳
     * @return 毫秒时间戳
     */
    private static long secondsToMillis(double epochSeconds) {
        return Math.round(epochSeconds * 1000.0);
    }
}
