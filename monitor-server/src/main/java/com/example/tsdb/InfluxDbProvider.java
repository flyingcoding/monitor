package com.example.tsdb;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.example.entity.dto.RuntimeData;
import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.entity.vo.response.RuntimeHistoryVO;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 默认 {@link TimeSeriesAdapter} 实现，封装 InfluxDB 2.x 客户端。
 *
 * <p>v2.0-alpha 之前的 {@code InfluxDbUtils} 的全部能力（写入、断路器降级、JSONL 缓冲与定时重放、
 * 历史与可用率查询）均迁入此类。当 {@code monitor.tsdb.provider=influxdb}（默认）时由
 * {@link TsdbAdapterFactory} 注入；当配置为其他值（如 {@code victoria-metrics}）时，工厂仍以
 * 本类作为回落实现。
 *
 * <h3>写入路径</h3>
 * <ul>
 *   <li>{@link #writeRuntime} / {@link #writeOtlpMetric} 都调用 {@link #doWriteRuntimeData}。</li>
 *   <li>外层包装 {@code @CircuitBreaker(name="influxdb", fallbackMethod=...)}，断路器配置见
 *       {@code application-{dev,prod}.yml} 的 {@code resilience4j.circuitbreaker.instances.influxdb}。</li>
 *   <li>断路器开启时 {@link #writeToFileBuffer} 把记录追加到 {@code monitor.influx-buffer.dir}
 *       目录下的 JSONL 文件；{@link #replayBufferedData} 周期性重放并归档。</li>
 * </ul>
 *
 * <h3>读取路径</h3>
 * <p>{@link #readRuntimeHistory} 和 {@link #readAvailabilityBuckets} 直接调 Flux Query；
 * 异常向上抛出由调用方决定降级（{@code StatusPageServiceImpl.computeSummary} 会兜底）。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "monitor.tsdb", name = "provider", havingValue = "influxdb", matchIfMissing = true)
public class InfluxDbProvider implements TimeSeriesAdapter {

    /** 24 小时可用率窗口长度（小时）。 */
    public static final int AVAILABILITY_WINDOW_HOURS = 24;

    /** 单个桶的时长（分钟）。 */
    public static final int BUCKET_MINUTES = 30;

    /** 24 小时窗口下的桶总数（{@link #AVAILABILITY_WINDOW_HOURS} * 60 / {@link #BUCKET_MINUTES}）。 */
    public static final int BUCKET_COUNT_24H = AVAILABILITY_WINDOW_HOURS * 60 / BUCKET_MINUTES;

    @Value("${spring.influx.url}")
    private String url;

    @Value("${spring.influx.user}")
    private String user;

    @Value("${spring.influx.password}")
    private String password;

    @Value("${spring.influx.bucket}")
    private String bucket;

    @Value("${spring.influx.organization}")
    private String organization;

    @Value("${monitor.influx-buffer.dir:data/influx-buffer}")
    private String bufferDir;

    @Value("${monitor.influx-buffer.batch-size:200}")
    private int replayBatchSize;

    private InfluxDBClient client;
    private WriteApiBlocking writeApi;
    private final ReentrantLock bufferLock = new ReentrantLock();

    /**
     * 初始化 InfluxDB 客户端与同步写入 API，并创建缓冲目录。
     */
    @PostConstruct
    public void init() {
        client = InfluxDBClientFactory.create(url, user, password.toCharArray());
        writeApi = client.getWriteApiBlocking();
        this.ensureBufferDirectories();
    }

    /**
     * 关闭 InfluxDB 客户端资源。
     */
    @PreDestroy
    public void close() {
        if (client != null) {
            client.close();
        }
    }

    @Override
    @CircuitBreaker(name = "influxdb", fallbackMethod = "writeToFileBuffer")
    public void writeRuntime(int clientId, RuntimeDetailVO vo) {
        this.doWriteRuntimeData(clientId, vo);
    }

    @Override
    @CircuitBreaker(name = "influxdb", fallbackMethod = "writeToFileBuffer")
    public void writeOtlpMetric(int clientId, RuntimeDetailVO vo) {
        this.doWriteRuntimeData(clientId, vo);
    }

    /**
     * 断路器回退逻辑：把运行时数据写入本地 JSONL 缓冲。
     *
     * @param clientId   客户端 ID
     * @param vo         运行时数据
     * @param throwable  触发回退的异常
     */
    private void writeToFileBuffer(int clientId, RuntimeDetailVO vo, Throwable throwable) {
        log.warn("InfluxDB 写入降级到本地缓冲，clientId={}, reason={}", clientId,
                throwable == null ? "unknown" : throwable.getMessage());
        this.appendBufferRecord(clientId, vo);
    }

    /**
     * 定时重放本地缓冲文件，按文件名顺序回放并在成功后归档。
     */
    @Scheduled(fixedDelayString = "${monitor.influx-buffer.replay-interval-ms:15000}")
    public void replayBufferedData() {
        if (!bufferLock.tryLock()) {
            return;
        }
        try {
            Path bufferPath = Path.of(bufferDir);
            if (!Files.exists(bufferPath)) {
                return;
            }
            List<Path> files;
            try (var stream = Files.list(bufferPath)) {
                files = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .limit(Math.max(replayBatchSize, 1))
                        .toList();
            }
            for (Path file : files) {
                if (!this.replaySingleFile(file)) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("重放 Influx 缓冲数据失败: {}", e.getMessage());
        } finally {
            bufferLock.unlock();
        }
    }

    @Override
    public RuntimeHistoryVO readRuntimeHistory(int clientId) {
        RuntimeHistoryVO vo = new RuntimeHistoryVO();
        String query = """
                from(bucket: "%s")
                |> range(start: %s)
                |> filter(fn: (r) => r["_measurement"] == "runtime")
                |> filter(fn: (r) => r["clientId"] == "%s")
                """;
        String format = String.format(query, bucket, "-1h", clientId);
        List<FluxTable> tables = client.getQueryApi().query(format, organization);
        int size = tables.size();
        if (size == 0) return vo;
        List<FluxRecord> records = tables.get(0).getRecords();
        for (int i = 0; i < records.size(); i++) {
            JSONObject object = new JSONObject();
            object.put("timestamp", records.get(i).getTime());
            for (int j = 0; j < size; j++) {
                FluxRecord record = tables.get(j).getRecords().get(i);
                object.put(record.getField(), record.getValue());
            }
            vo.getList().add(object);
        }
        return vo;
    }

    @Override
    public double[] readAvailabilityBuckets(int clientId) {
        String flux = String.format("""
                from(bucket: "%s")
                  |> range(start: -%dh)
                  |> filter(fn: (r) => r["_measurement"] == "runtime")
                  |> filter(fn: (r) => r["clientId"] == "%s")
                  |> filter(fn: (r) => r["_field"] == "cpuUsage")
                  |> aggregateWindow(every: %dm, fn: count, createEmpty: true)
                """, bucket, AVAILABILITY_WINDOW_HOURS, clientId, BUCKET_MINUTES);
        List<FluxTable> tables = client.getQueryApi().query(flux, organization);
        if (tables.isEmpty()) {
            return new double[0];
        }
        List<FluxRecord> records = tables.get(0).getRecords();
        if (records.isEmpty()) {
            return new double[0];
        }
        int from = Math.max(0, records.size() - BUCKET_COUNT_24H);
        double[] buckets = new double[BUCKET_COUNT_24H];
        int offset = BUCKET_COUNT_24H - (records.size() - from);
        for (int i = from; i < records.size(); i++) {
            Object value = records.get(i).getValue();
            long count = value instanceof Number n ? n.longValue() : 0L;
            buckets[offset + (i - from)] = count > 0 ? 1.0 : 0.0;
        }
        return buckets;
    }

    /**
     * 执行实际的 InfluxDB 写入。
     *
     * @param clientId 客户端 ID
     * @param vo       运行时数据
     */
    private void doWriteRuntimeData(int clientId, RuntimeDetailVO vo) {
        RuntimeData data = new RuntimeData();
        BeanUtils.copyProperties(vo, data);
        data.setClientId(clientId);
        data.setTimestamp(new Date(vo.getTimestamp()).toInstant());
        writeApi.writeMeasurement(bucket, organization, WritePrecision.NS, data);
    }

    /**
     * 将降级数据追加到本地 JSONL 缓冲文件。
     *
     * @param clientId 客户端 ID
     * @param vo       运行时数据
     */
    private void appendBufferRecord(int clientId, RuntimeDetailVO vo) {
        bufferLock.lock();
        try {
            this.ensureBufferDirectories();
            InfluxBufferRecord record = new InfluxBufferRecord();
            record.setClientId(clientId);
            record.setRuntime(vo);
            record.setBufferedAt(Instant.now().toEpochMilli());

            String fileName = record.getBufferedAt() + "-" + UUID.randomUUID() + ".jsonl";
            Path target = Path.of(bufferDir, fileName);
            Files.writeString(
                    target,
                    JSON.toJSONString(record) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (Exception e) {
            log.error("写入 Influx 本地缓冲失败", e);
        } finally {
            bufferLock.unlock();
        }
    }

    /**
     * 重放单个缓冲文件并归档。
     *
     * @param file 缓冲文件路径
     * @return 是否重放成功
     */
    private boolean replaySingleFile(Path file) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                InfluxBufferRecord record = JSON.parseObject(line, InfluxBufferRecord.class);
                if (record == null || record.getRuntime() == null) {
                    continue;
                }
                this.doWriteRuntimeData(record.getClientId(), record.getRuntime());
            }
            this.archiveFile(file);
            return true;
        } catch (Exception e) {
            log.warn("重放缓冲文件失败，等待下次重试: file={}, reason={}", file.getFileName(), e.getMessage());
            return false;
        }
    }

    /**
     * 将成功重放的缓冲文件移动到归档目录。
     *
     * @param file 缓冲文件
     * @throws IOException 文件移动异常
     */
    private void archiveFile(Path file) throws IOException {
        Path archiveDir = Path.of(bufferDir, "archive");
        if (!Files.exists(archiveDir)) {
            Files.createDirectories(archiveDir);
        }
        Path target = archiveDir.resolve(file.getFileName());
        Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * 创建缓冲目录与归档目录。
     */
    private void ensureBufferDirectories() {
        try {
            Files.createDirectories(Path.of(bufferDir));
            Files.createDirectories(Path.of(bufferDir, "archive"));
        } catch (IOException e) {
            log.error("初始化 Influx 缓冲目录失败", e);
        }
    }

    /**
     * Influx 缓冲记录结构。
     */
    public static class InfluxBufferRecord {
        private int clientId;
        private RuntimeDetailVO runtime;
        private long bufferedAt;

        /** @return 客户端 ID */
        public int getClientId() {
            return clientId;
        }

        /** @param clientId 客户端 ID */
        public void setClientId(int clientId) {
            this.clientId = clientId;
        }

        /** @return 运行时数据 */
        public RuntimeDetailVO getRuntime() {
            return runtime;
        }

        /** @param runtime 运行时数据 */
        public void setRuntime(RuntimeDetailVO runtime) {
            this.runtime = runtime;
        }

        /** @return 写入缓冲时间戳（毫秒） */
        public long getBufferedAt() {
            return bufferedAt;
        }

        /** @param bufferedAt 毫秒时间戳 */
        public void setBufferedAt(long bufferedAt) {
            this.bufferedAt = bufferedAt;
        }
    }
}
