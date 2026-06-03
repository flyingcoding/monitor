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
import java.util.LinkedHashMap;
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
 *       同一份断路器配置可控制两种 provider 的 fallback 节奏；fallback 只写共享 JSONL 缓冲，
 *       不把 VM 失败样本误写入 InfluxDB。</li>
 *   <li><b>JSONL 缓冲复用</b>：直接注入 {@link InfluxDbProvider} 作为 fallback 写入目标。
 *       这避免重写一份 buffer 路径，并保证切换 provider 时旧缓冲可被重放。</li>
 *   <li><b>查询路径</b>：用 Spring {@link RestClient} 调 VM HTTP API：
 *       <ul>
 *         <li>{@link #readRuntimeHistory}：通过 {@code /api/v1/export} 读取指定时间范围内
 *             {@code runtime_*} 原始样本，按时间戳合并为 VO 列表；
 *             下采样口径由 {@link TsdbQueryUtils#chooseStep} 决定，
 *             短窗口（≤ 1h）保留原生 10s 采样，长窗口（≤ 7d）按 10min 桶聚合，
 *             保证返回点数稳定在 1k-2k 区间；</li>
 *         <li>{@link #readAvailabilityBuckets}：用 MetricsQL 扩展函数 {@code present_over_time}
 *             通过 {@code /api/v1/query_range} 返回 48 个 0/1 桶，对应 24h × 30min。</li>
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

    /** VM JSON line raw sample export 端点。 */
    public static final String EXPORT_PATH = "/api/v1/export";

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

        log.info("VictoriaMetricsProvider 已初始化，写入端点={}/api/v2/write，原始样本端点={}{}，查询端点={}{}（超时 {}ms）",
                url, url, EXPORT_PATH, url, QUERY_RANGE_PATH, queryTimeoutMs);
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
    @CircuitBreaker(name = "tsdb", fallbackMethod = "writeBatchToFallbackBuffer")
    public void writeRuntimeBatch(int clientId, List<RuntimeDetailVO> batch) {
        this.doWriteRuntimeDataBatch(clientId, batch);
    }

    @Override
    @CircuitBreaker(name = "tsdb", fallbackMethod = "writeToFallbackBuffer")
    public void writeOtlpMetric(int clientId, RuntimeDetailVO vo) {
        this.doWriteRuntimeData(clientId, vo);
    }

    /**
     * 断路器回退逻辑：VM 写入失败时把数据交给共享 JSONL 缓冲（{@link InfluxDbProvider}），
     * 由 {@link InfluxDbProvider#replayBufferedData} 后台调度重放——重放时走当前活跃 provider，
     * 也就是 VM 自身；这样确保 v1.x 缓冲与 v2.0-beta 缓冲共享同一份"未传送"队列。
     *
     * @param clientId  客户端 ID
     * @param vo        运行时数据
     * @param throwable 触发回退的异常
     */
    private void writeToFallbackBuffer(int clientId, RuntimeDetailVO vo, Throwable throwable) {
        log.warn("VictoriaMetrics 写入降级到本地缓冲，clientId={}, reason={}", clientId,
                throwable == null ? "unknown" : throwable.getMessage());
        fallbackBuffer.bufferRuntime(clientId, vo);
    }

    /**
     * 断路器批量回退逻辑：VM 批量写入失败时把整批样本写入共享 JSONL 缓冲。
     *
     * @param clientId  客户端 ID
     * @param batch     运行时数据批次
     * @param throwable 触发回退的异常
     */
    private void writeBatchToFallbackBuffer(int clientId, List<RuntimeDetailVO> batch, Throwable throwable) {
        int size = batch == null ? 0 : batch.size();
        log.warn("VictoriaMetrics 批量写入降级到本地缓冲，clientId={}, size={}, reason={}", clientId, size,
                throwable == null ? "unknown" : throwable.getMessage());
        fallbackBuffer.bufferRuntimeBatch(clientId, batch);
    }

    /**
     * 把 {@link RuntimeDetailVO} 转换为 {@link RuntimeData} 并通过 line protocol 写入 VM。
     *
     * @param clientId 客户端 ID
     * @param vo       运行时数据
     */
    private void doWriteRuntimeData(int clientId, RuntimeDetailVO vo) {
        RuntimeData data = this.toRuntimeData(clientId, vo);
        if (data == null) {
            return;
        }
        // VM 忽略 bucket / org，但 SDK 必填，传占位常量
        writeApi.writeMeasurement(
                VM_PLACEHOLDER_TOKEN_OR_BUCKET,
                VM_PLACEHOLDER_ORG,
                WritePrecision.NS,
                data);
    }

    /**
     * 批量转换并写入 VM 兼容 InfluxDB line protocol 端点。
     *
     * @param clientId 客户端 ID
     * @param batch    运行时数据批次
     */
    private void doWriteRuntimeDataBatch(int clientId, List<RuntimeDetailVO> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        List<RuntimeData> data = batch.stream()
                .map(vo -> this.toRuntimeData(clientId, vo))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (data.isEmpty()) {
            return;
        }
        // VM 忽略 bucket / org，但 SDK 必填，传占位常量
        writeApi.writeMeasurements(
                VM_PLACEHOLDER_TOKEN_OR_BUCKET,
                VM_PLACEHOLDER_ORG,
                WritePrecision.NS,
                data);
    }

    /**
     * 把 {@link RuntimeDetailVO} 转换为 {@link RuntimeData}。
     *
     * @param clientId 客户端 ID
     * @param vo       运行时数据
     * @return measurement DTO；输入为空时返回 null
     */
    private RuntimeData toRuntimeData(int clientId, RuntimeDetailVO vo) {
        if (vo == null) {
            return null;
        }
        RuntimeData data = new RuntimeData();
        BeanUtils.copyProperties(vo, data);
        data.setClientId(clientId);
        data.setTimestamp(new Date(vo.getTimestamp()).toInstant());
        return data;
    }

    @Override
    public RuntimeHistoryVO readRuntimeHistory(int clientId, Instant from, Instant to) {
        RuntimeHistoryVO vo = new RuntimeHistoryVO();
        String selector = String.format("{__name__=~\"%s.*\",clientId=\"%d\"}", METRIC_NAME_PREFIX, clientId);
        String response;
        try {
            response = this.exportRawSamples(selector, from, to);
        } catch (RestClientException e) {
            log.warn("VictoriaMetrics readRuntimeHistory 原始样本导出失败 clientId={}: {}", clientId, e.getMessage());
            throw e;
        }
        if (response == null || response.isBlank()) {
            return vo;
        }
        // 按时间戳聚合：TreeMap 保证按时间戳升序输出，与 InfluxDB 表现一致
        Map<Long, JSONObject> byTimestamp = new TreeMap<>();
        for (String line : response.split("\\R")) {
            if (line == null || line.isBlank()) {
                continue;
            }
            JSONObject series = JSON.parseObject(line);
            JSONObject metric = series.getJSONObject("metric");
            if (metric == null) {
                continue;
            }
            String metricName = metric.getString("__name__");
            if (metricName == null || !metricName.startsWith(METRIC_NAME_PREFIX)) {
                continue;
            }
            String fieldName = metricName.substring(METRIC_NAME_PREFIX.length());
            JSONArray timestamps = series.getJSONArray("timestamps");
            JSONArray values = series.getJSONArray("values");
            if (timestamps == null || values == null) {
                continue;
            }
            int points = Math.min(timestamps.size(), values.size());
            for (int j = 0; j < points; j++) {
                Long tsMillis = timestamps.getLong(j);
                if (tsMillis == null) continue;
                Object rawValue = parsePromValue(values.getString(j));
                JSONObject row = byTimestamp.computeIfAbsent(tsMillis, ts -> {
                    JSONObject obj = new JSONObject();
                    obj.put("timestamp", Instant.ofEpochMilli(ts));
                    return obj;
                });
                row.put(fieldName, rawValue);
            }
        }
        // 短窗口（≤ 1h，step=10s）原样返回；长窗口按 TsdbQueryUtils.chooseStep 服务端下采样，
        // 与 InfluxDB aggregateWindow 口径对齐（mean），保证两个 provider 单次返回点数稳定 ≤ 2k。
        Duration window = Duration.between(from, to);
        Duration step = TsdbQueryUtils.chooseStep(window);
        if (step.equals(TsdbQueryUtils.STEP_10S)) {
            vo.getList().addAll(byTimestamp.values());
        } else {
            vo.getList().addAll(this.downsampleByMean(byTimestamp, step));
        }
        return vo;
    }

    /**
     * 把 VM /api/v1/export 返回的原始样本按 step 时间桶分组并取每桶平均值，
     * 与 InfluxDB aggregateWindow(fn: mean) 口径对齐。
     *
     * <p>排除非数值字段（如 timestamp 自身、字符串型 NaN/Inf）；空桶不输出。
     *
     * @param byTimestamp 按时间戳升序的原始行 map
     * @param step        聚合 step
     * @return 按 step 起始时间升序的聚合行
     */
    private List<JSONObject> downsampleByMean(Map<Long, JSONObject> byTimestamp, Duration step) {
        long stepMillis = step.toMillis();
        if (stepMillis <= 0) {
            return new ArrayList<>(byTimestamp.values());
        }
        // bucketStartMillis -> field -> running sum/count
        Map<Long, Map<String, double[]>> buckets = new TreeMap<>();
        for (Map.Entry<Long, JSONObject> entry : byTimestamp.entrySet()) {
            long ts = entry.getKey();
            long bucketStart = (ts / stepMillis) * stepMillis;
            Map<String, double[]> agg = buckets.computeIfAbsent(bucketStart, k -> new LinkedHashMap<>());
            JSONObject row = entry.getValue();
            for (Map.Entry<String, Object> field : row.entrySet()) {
                if ("timestamp".equals(field.getKey())) continue;
                Object value = field.getValue();
                if (!(value instanceof Number n)) continue;
                double d = n.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) continue;
                double[] sumCount = agg.computeIfAbsent(field.getKey(), k -> new double[2]);
                sumCount[0] += d;
                sumCount[1] += 1;
            }
        }
        List<JSONObject> out = new ArrayList<>(buckets.size());
        for (Map.Entry<Long, Map<String, double[]>> entry : buckets.entrySet()) {
            Map<String, double[]> agg = entry.getValue();
            if (agg.isEmpty()) continue;
            JSONObject row = new JSONObject();
            row.put("timestamp", Instant.ofEpochMilli(entry.getKey()));
            for (Map.Entry<String, double[]> f : agg.entrySet()) {
                double[] sumCount = f.getValue();
                if (sumCount[1] > 0) {
                    row.put(f.getKey(), sumCount[0] / sumCount[1]);
                }
            }
            out.add(row);
        }
        return out;
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
        double[] buckets = new double[InfluxDbProvider.BUCKET_COUNT_24H];
        long lastBucketEndEpochSecond = end.getEpochSecond();
        for (int i = 0; i < values.size(); i++) {
            JSONArray point = values.getJSONArray(i);
            if (point == null || point.size() < 2) {
                continue;
            }
            int bucketIndex = availabilityBucketIndex(point.getDouble(0), lastBucketEndEpochSecond);
            if (bucketIndex < 0) {
                continue;
            }
            String raw = point.getString(1);
            double v = "NaN".equalsIgnoreCase(raw) ? 0.0 : Double.parseDouble(raw);
            buckets[bucketIndex] = v > 0 ? 1.0 : 0.0;
        }
        return buckets;
    }

    /**
     * 将 VM query_range 返回的样本时间戳映射到 24h 内的 48 个 30 分钟桶。
     *
     * <p>query_range 可能返回稀疏点；不能只按返回顺序右对齐，否则旧样本会被误认为最近在线。
     * 桶语义与原 InfluxDB 路径保持一致：忽略 24h 窗口最左边界上的额外点，仅保留从
     * {@code end - 23.5h} 到 {@code end} 的 48 个桶。
     *
     * @param epochSeconds             VM 返回的浮点秒时间戳
     * @param lastBucketEndEpochSecond 最后一个桶的结束时间（查询 end）
     * @return 0..47 的桶索引；窗口外返回 -1
     */
    private static int availabilityBucketIndex(Double epochSeconds, long lastBucketEndEpochSecond) {
        if (epochSeconds == null) {
            return -1;
        }
        long pointEpochSecond = Math.round(epochSeconds);
        long offsetSteps = Math.round((double) (lastBucketEndEpochSecond - pointEpochSecond) / AVAILABILITY_STEP_SECONDS);
        if (offsetSteps < 0 || offsetSteps >= InfluxDbProvider.BUCKET_COUNT_24H) {
            return -1;
        }
        return InfluxDbProvider.BUCKET_COUNT_24H - 1 - (int) offsetSteps;
    }

    /**
     * 调 VM {@code /api/v1/export} 读取原始样本 JSONL。
     *
     * <p>不能用 {@code /api/v1/query_range} 获取历史曲线原始点：range query 会按 step 多次求值，
     * 可能通过 lookback 合成不存在的采样点。export API 返回存储中的 {@code values/timestamps}。
     *
     * @param selector time series selector
     * @param start    起始时间
     * @param end      结束时间
     * @return JSON line 字符串；无数据时可能为空
     */
    private String exportRawSamples(String selector, Instant start, Instant end) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("match[]", selector);
        form.add("start", String.valueOf(start.getEpochSecond()));
        form.add("end", String.valueOf(end.getEpochSecond()));
        form.add("reduce_mem_usage", "1");
        return queryClient.post()
                .uri(EXPORT_PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
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

}
