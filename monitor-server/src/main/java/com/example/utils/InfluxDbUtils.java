package com.example.utils;

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

@Slf4j
@Component
public class InfluxDbUtils {

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
     * 初始化 InfluxDB 客户端与同步写入 API。
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

    /**
     * 写入运行时数据，异常时触发断路器回退到本地 JSONL 缓冲。
     *
     * @param clientId 客户端ID
     * @param vo 运行时数据
     */
    @CircuitBreaker(name = "influxdb", fallbackMethod = "writeToFileBuffer")
    public void writeRuntimeData(int clientId, RuntimeDetailVO vo) {
        this.doWriteRuntimeData(clientId, vo);
    }

    /**
     * 断路器回退逻辑：将运行时数据写入本地 JSONL 文件缓冲。
     *
     * @param clientId 客户端ID
     * @param vo 运行时数据
     * @param throwable 触发回退的异常
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

    /**
     * 查询客户端历史运行时数据。
     *
     * @param clientId 客户端ID
     * @return 历史运行时数据
     */
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

    /**
     * 查询客户端最近 24 小时按 30 分钟切分的可用率桶序列。
     *
     * <p>实现思路（公开状态页方案 A，参考 {@code status-page-design.md} §2 / §5）：
     * <ul>
     *   <li>使用一次 Flux {@code aggregateWindow(every: 30m, fn: count, createEmpty: true)}
     *       让 InfluxDB 直接补齐缺失桶；</li>
     *   <li>过滤单一 {@code cpuUsage} field 避免多 field 产生多张表；</li>
     *   <li>每个桶有数据点视为 1.0（在线），无数据点视为 0.0（离线）；</li>
     *   <li>返回值长度恒为 {@link #BUCKET_COUNT_24H}（48），oldest → newest 顺序，
     *       供前端绘制柱条；调用方据此计算总可用率（{@code sum / 48}）。</li>
     * </ul>
     *
     * <p>所有 Influx 异常向上抛出由调用方决定降级策略（缓存兜底或返回 {@code null}）；
     * 本工具类不在此处做断路器，因为 status page 是只读路径，{@link CircuitBreaker} 仅
     * 守在写入路径以防写入阻塞采集流。
     *
     * @param clientId 客户端ID
     * @return 48 个桶的可用率数组（0.0 或 1.0）；Influx 没有任何数据时返回长度为 0 的空数组
     */
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
        // 多 series 可能共享同一时间桶；按 _stop 时间合并取最大 count > 0
        List<FluxRecord> records = tables.get(0).getRecords();
        if (records.isEmpty()) {
            return new double[0];
        }
        // aggregateWindow 返回的桶数量可能 ≥ BUCKET_COUNT_24H（边界向上取整），
        // 截取最后 48 个，确保 oldest→newest 长度恒为 48。
        int from = Math.max(0, records.size() - BUCKET_COUNT_24H);
        double[] buckets = new double[BUCKET_COUNT_24H];
        // 当 records 少于 48 时，前面填 0（表示该时间段尚未上线）
        int offset = BUCKET_COUNT_24H - (records.size() - from);
        for (int i = from; i < records.size(); i++) {
            Object value = records.get(i).getValue();
            long count = value instanceof Number n ? n.longValue() : 0L;
            buckets[offset + (i - from)] = count > 0 ? 1.0 : 0.0;
        }
        return buckets;
    }

    /**
     * 24 小时可用率窗口长度（小时）。
     */
    public static final int AVAILABILITY_WINDOW_HOURS = 24;

    /**
     * 单个桶的时长（分钟）。
     */
    public static final int BUCKET_MINUTES = 30;

    /**
     * 24 小时窗口下的桶总数（{@link #AVAILABILITY_WINDOW_HOURS} * 60 / {@link #BUCKET_MINUTES}）。
     */
    public static final int BUCKET_COUNT_24H =
            AVAILABILITY_WINDOW_HOURS * 60 / BUCKET_MINUTES;

    /**
     * 执行实际的 InfluxDB 写入。
     *
     * @param clientId 客户端ID
     * @param vo 运行时数据
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
     * @param clientId 客户端ID
     * @param vo 运行时数据
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

        /**
         * 获取客户端ID。
         *
         * @return 客户端ID
         */
        public int getClientId() {
            return clientId;
        }

        /**
         * 设置客户端ID。
         *
         * @param clientId 客户端ID
         */
        public void setClientId(int clientId) {
            this.clientId = clientId;
        }

        /**
         * 获取运行时数据。
         *
         * @return 运行时数据
         */
        public RuntimeDetailVO getRuntime() {
            return runtime;
        }

        /**
         * 设置运行时数据。
         *
         * @param runtime 运行时数据
         */
        public void setRuntime(RuntimeDetailVO runtime) {
            this.runtime = runtime;
        }

        /**
         * 获取写入缓冲时间戳。
         *
         * @return 毫秒时间戳
         */
        public long getBufferedAt() {
            return bufferedAt;
        }

        /**
         * 设置写入缓冲时间戳。
         *
         * @param bufferedAt 毫秒时间戳
         */
        public void setBufferedAt(long bufferedAt) {
            this.bufferedAt = bufferedAt;
        }
    }
}
