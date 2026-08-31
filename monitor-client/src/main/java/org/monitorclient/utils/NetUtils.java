package org.monitorclient.utils;

import com.alibaba.fastjson2.JSON;
import org.monitorclient.collector.*;
import org.monitorclient.entity.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Queues reports without I/O; one transport worker performs bounded, backoff-controlled sends. */
public class NetUtils {
    private static final Logger log = LoggerFactory.getLogger(NetUtils.class);
    private static final int MAX_REQUEST_BYTES = 262144;
    private static final int MAX_RESPONSE_BYTES = 65536;
    private static final long SNAPSHOT_TTL_NANOS = TimeUnit.MINUTES.toNanos(5);
    private final Map<String, Snapshot> snapshots = new LinkedHashMap<>();
    private final AtomicBoolean flushing = new AtomicBoolean();
    private final LongSupplier clock;
    private volatile ConnectionConfig config;
    private volatile BaseDetail pendingBaseDetail;
    private volatile boolean heartbeat;
    private volatile boolean closed;
    private long nextAttempt;
    private int consecutiveFailures;
    private long lastWarning;

    /** Uses a monotonic clock unaffected by NTP corrections. */
    public NetUtils() { this(System::nanoTime); }
    /** Allows deterministic backoff tests without sleeps. */
    NetUtils(LongSupplier clock) { this.clock = clock; }
    /** Recognizes an existing node before consuming a one-time registration token. */
    public boolean registerToServer(String address, String token) {
        ConnectionConfig candidate = new ConnectionConfig(address, token);
        Response existing = request("GET", "/heartbeat", null, candidate);
        if (existing.success()) return true;
        if (existing.code() != 401 && existing.code() != 403) return false;
        return request("GET", "/register", null, candidate).success();
    }

    /** Sets pre-provisioned credentials without logging their value. */
    public void setConfig(ConnectionConfig config) { this.config = config; }
    /** Retains one metadata payload until the server acknowledges it. */
    public void updateBaseDetails(BaseDetail detail) { this.pendingBaseDetail = detail; }
    /** Requests a connectivity probe without blocking the collection thread. */
    public void sendHeartbeat() { this.heartbeat = true; }
    /** Enqueues one sample in a bounded queue; no retries or sleeps run on this thread. */
    public void updateRuntimeDetails(RuntimeDetail detail) { if (!closed) LocalCacheUtils.offer(detail); }

    /** Retains only the most recent systemd snapshot. */
    public void postSystemdSnapshot(List<SystemdUnitStat> snapshot) { queueSnapshot("/systemd", envelope("units", snapshot)); }
    /** Retains only the most recent disk health snapshot. */
    public void postSmartSnapshot(List<SmartStat> snapshot) { queueSnapshot("/smart", envelope("disks", snapshot)); }
    /** Retains only the most recent GPU snapshot. */
    public void postGpuSnapshot(List<GpuStat> snapshot) { queueSnapshot("/gpu", envelope("gpus", snapshot)); }
    /** Retains only the most recent process snapshot. */
    public void postProcessSnapshot(ProcessSnapshot snapshot) { queueSnapshot("/process", snapshot); }

    /** Preserves the original collector wire envelope. */
    private Object envelope(String key, Object value) {
        return value == null ? null : Collections.singletonMap(key, value);
    }

    /** Bounds retained snapshots by four fixed keys and 256 KiB per serialized value. */
    private void queueSnapshot(String path, Object value) {
        if (value == null || closed) return;
        String json = JSON.toJSONString(value);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES) {
            warn("Oversized collector snapshot was dropped");
            return;
        }
        synchronized (snapshots) {
            // Reinsertion gives other collectors a fair opportunity to upload.
            snapshots.put(path, new Snapshot(json, clock.getAsLong()));
        }
    }

    /** Sends at most one metric batch, one metadata payload and one snapshot per invocation. */
    public void flushCachedData() {
        if (closed || !flushing.compareAndSet(false, true)) return;
        try {
            if (consecutiveFailures > 0 && clock.getAsLong() - nextAttempt < 0) return;
            List<RuntimeDetail> batch = LocalCacheUtils.drainBatch();
            if (!batch.isEmpty()) {
                Response response;
                try {
                    // Original server applies batch side effects in wire order; newest must be last.
                    batch.sort(Comparator.comparingLong(RuntimeDetail::getTimestamp));
                    response = request("POST", "/runtime/batch", JSON.toJSONString(batch));
                }
                catch (RuntimeException error) {
                    LocalCacheUtils.requeueUnsentBatch(batch, 0);
                    failed(500);
                    return;
                }
                if (!response.success()) {
                    if (response.code() == 400 || response.code() == 413) {
                        // A permanently invalid batch must not poison replay forever.
                        warn("Server rejected a runtime batch; invalid samples were discarded");
                    } else LocalCacheUtils.requeueUnsentBatch(batch, 0);
                    failed(response.code());
                    return;
                }
                heartbeat = false;
            } else if (heartbeat) {
                Response response = request("GET", "/heartbeat", null);
                if (!response.success()) { failed(response.code()); return; }
                heartbeat = false;
            }
            if (closed || Thread.currentThread().isInterrupted()) return;
            BaseDetail detail = pendingBaseDetail;
            if (detail != null) {
                Response response = request("POST", "/detail", JSON.toJSONString(detail));
                if (!response.success()) {
                    warn("Static metadata upload failed (status=" + response.code() + ")");
                    if (response.code() == 400 || response.code() == 413) pendingBaseDetail = null;
                } else if (pendingBaseDetail == detail) pendingBaseDetail = null;
            }
            if (closed || Thread.currentThread().isInterrupted()) return;
            Map.Entry<String, Snapshot> snapshot = takeSnapshot();
            if (snapshot != null) {
                Response response = request("POST", snapshot.getKey(), snapshot.getValue().json);
                if (!response.success()) {
                    if (response.code() != 400 && response.code() != 413) {
                        synchronized (snapshots) { snapshots.putIfAbsent(snapshot.getKey(), snapshot.getValue()); }
                    }
                    warn("Optional snapshot upload failed (status=" + response.code() + ")");
                }
            }
            if (consecutiveFailures > 0) log.info("Server connection recovered; queued={}, dropped={}",
                    LocalCacheUtils.size(), LocalCacheUtils.droppedCount());
            consecutiveFailures = 0;
            nextAttempt = clock.getAsLong();
        } finally { flushing.set(false); }
    }

    /** Removes expired snapshots and selects a single retained value in round-robin order. */
    private Map.Entry<String, Snapshot> takeSnapshot() {
        synchronized (snapshots) {
            Iterator<Map.Entry<String, Snapshot>> iterator = snapshots.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Snapshot> entry = iterator.next();
                iterator.remove();
                if (clock.getAsLong() - entry.getValue().created < SNAPSHOT_TTL_NANOS) {
                    return new AbstractMap.SimpleImmutableEntry<>(entry);
                }
            }
        }
        return null;
    }

    /** Backs off without sleeping; unavailable endpoints cannot trigger busy-loop retries. */
    private void failed(int status) {
        consecutiveFailures = Math.min(10, consecutiveFailures + 1);
        long seconds = status == 401 || status == 403 ? 300 : Math.min(60, 1L << consecutiveFailures);
        nextAttempt = clock.getAsLong() + TimeUnit.SECONDS.toNanos(seconds);
        warn("Report failed (status=" + status + "); retry delayed, queued=" + LocalCacheUtils.size()
                + ", dropped=" + LocalCacheUtils.droppedCount());
    }

    /** Limits repeated transport/configuration warnings to one per minute. */
    private synchronized void warn(String message) {
        long now = clock.getAsLong();
        if (lastWarning == 0 || now - lastWarning >= TimeUnit.MINUTES.toNanos(1)) {
            lastWarning = now;
            log.warn(message);
        }
    }

    /** Stops subsequent requests; an in-flight worker is daemonized and externally supervised. */
    public void close() { closed = true; }

    /** Sends one best-effort offline notice only when no data request is in flight. */
    public void notifyShutdown() {
        if (!flushing.compareAndSet(false, true)) return;
        try { request("GET", "/offline", null); }
        finally { flushing.set(false); }
    }

    /** Uses finite read/connect timeouts, bounded bodies and no credential-bearing redirects. */
    protected Response request(String method, String path, String json) {
        return request(method, path, json, config);
    }

    /** Uses a candidate configuration for startup registration without replacing runtime credentials. */
    private Response request(String method, String path, String json, ConnectionConfig current) {
        if (current == null) return new Response(503, null, "Connection configuration is missing");
        HttpURLConnection connection = null;
        long deadline = clock.getAsLong() + TimeUnit.SECONDS.toNanos(15);
        try {
            connection = (HttpURLConnection) new URL(current.getAddress() + "/monitor" + path).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Authorization", current.getToken());
            connection.setRequestProperty("Accept", "application/json");
            if (json != null) {
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                if (body.length > MAX_REQUEST_BYTES) return new Response(413, null, "Request is too large");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) return new Response(status, null, "Server rejected request");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int size;
                while ((size = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted() || clock.getAsLong() - deadline >= 0) throw new IOException("Response deadline exceeded");
                    if (output.size() + size > MAX_RESPONSE_BYTES) throw new IOException("Response exceeds 64 KiB");
                    output.write(buffer, 0, size);
                }
                return Response.parse(new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (Exception error) {
            // Do not retain response bodies, URLs, tokens or arbitrary server exception messages.
            return new Response(503, null, "Transport or response decoding failed");
        } finally { if (connection != null) connection.disconnect(); }
    }

    /** Holds one immutable serialized snapshot and its local monotonic creation time. */
    private static final class Snapshot {
        private final String json;
        private final long created;
        /** Captures a bounded, immutable snapshot for another thread. */
        private Snapshot(String json, long created) { this.json = json; this.created = created; }
    }
}
