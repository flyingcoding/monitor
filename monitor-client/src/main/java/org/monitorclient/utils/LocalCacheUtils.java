package org.monitorclient.utils;

import org.monitorclient.entity.RuntimeDetail;
import java.util.*;

/** Holds only bounded scalar samples; collection never waits for network I/O. */
public final class LocalCacheUtils {
    private static final int MAX_CACHE_SIZE = 1000;
    private static final int FLUSH_BATCH_SIZE = 50;
    private static final NavigableMap<Long, RuntimeDetail> CACHE = new TreeMap<>();
    private static long dropped;

    /** Prevents construction of this process-local queue. */
    private LocalCacheUtils() { }

    /** Keeps the newest sample for each timestamp and evicts the oldest on overflow. */
    public static synchronized boolean offer(RuntimeDetail detail) {
        if (detail == null) return false;
        CACHE.put(detail.getTimestamp(), detail);
        trim();
        return true;
    }

    /** Reports queued samples without exposing the queue. */
    public static synchronized int size() { return CACHE.size(); }
    /** Reports whether a transport attempt has work available. */
    public static synchronized boolean isEmpty() { return CACHE.isEmpty(); }
    /** Exposes accumulated overflow loss for rate-limited operational summaries. */
    public static synchronized long droppedCount() { return dropped; }

    /** Sends the newest sample first, then at most 49 oldest retained samples. */
    public static synchronized List<RuntimeDetail> drainBatch() {
        List<RuntimeDetail> batch = new ArrayList<>(FLUSH_BATCH_SIZE);
        if (CACHE.isEmpty()) return batch;
        batch.add(CACHE.pollLastEntry().getValue());
        while (batch.size() < FLUSH_BATCH_SIZE && !CACHE.isEmpty()) batch.add(CACHE.pollFirstEntry().getValue());
        return batch;
    }

    /** Merges failed samples without evicting newer data collected during the request. */
    public static synchronized void requeueUnsentBatch(List<RuntimeDetail> batch, int startIndex) {
        if (batch == null || startIndex < 0 || startIndex >= batch.size()) return;
        for (int i = startIndex; i < batch.size(); i++) {
            RuntimeDetail sample = batch.get(i);
            if (sample != null) CACHE.putIfAbsent(sample.getTimestamp(), sample);
        }
        trim();
    }

    /** Enforces the same capacity for collection and replay rollback. */
    private static void trim() {
        while (CACHE.size() > MAX_CACHE_SIZE) {
            CACHE.pollFirstEntry();
            dropped++;
        }
    }
}
