package org.monitorclient.utils;

import lombok.extern.slf4j.Slf4j;
import org.monitorclient.entity.RuntimeDetail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

@Slf4j
public class LocalCacheUtils {

    private static final int MAX_CACHE_SIZE = 1000;
    private static final int FLUSH_BATCH_SIZE = 50;
    private static final long FLUSH_BATCH_INTERVAL_MS = 100;

    private static final LinkedBlockingDeque<RuntimeDetail> cache = new LinkedBlockingDeque<>(MAX_CACHE_SIZE);

    /**
     * 向本地缓存追加运行时数据，缓存满时淘汰最旧的一条记录。
     *
     * @param detail 运行时数据
     * @return 是否成功写入缓存
     */
    public static synchronized boolean offer(RuntimeDetail detail) {
        if (detail == null) return false;
        boolean added = cache.offerLast(detail);
        if (!added) {
            log.warn("本地缓存已满（{}条），丢弃最早的数据", MAX_CACHE_SIZE);
            cache.pollFirst();
            added = cache.offerLast(detail);
        }
        return added;
    }

    /**
     * 获取当前缓存数据条数。
     *
     * @return 缓存条数
     */
    public static synchronized int size() {
        return cache.size();
    }

    /**
     * 判断本地缓存是否为空。
     *
     * @return 是否为空
     */
    public static synchronized boolean isEmpty() {
        return cache.isEmpty();
    }

    /**
     * 批量取出待补报的数据。
     *
     * @return 本次待补报批次
     */
    public static synchronized List<RuntimeDetail> drainBatch() {
        List<RuntimeDetail> batch = new ArrayList<>(FLUSH_BATCH_SIZE);
        cache.drainTo(batch, FLUSH_BATCH_SIZE);
        return batch;
    }

    /**
     * 将补报失败后尚未发送的数据放回队列头部，保证后续重试不丢数据。
     *
     * @param batch      当前批次数据
     * @param startIndex 批次内失败条目的索引（包含该索引）
     */
    public static synchronized void requeueUnsentBatch(List<RuntimeDetail> batch, int startIndex) {
        if (batch == null || batch.isEmpty() || startIndex < 0 || startIndex >= batch.size()) return;
        for (int i = batch.size() - 1; i >= startIndex; i--) {
            RuntimeDetail detail = batch.get(i);
            if (detail == null) continue;
            boolean added = cache.offerFirst(detail);
            if (!added) {
                log.warn("本地缓存已满（{}条），回补失败批次时淘汰最新的数据", MAX_CACHE_SIZE);
                cache.pollLast();
                cache.offerFirst(detail);
            }
        }
    }

    /**
     * 获取补报批次间隔时间。
     *
     * @return 批次间隔毫秒数
     */
    public static long getFlushBatchIntervalMs() {
        return FLUSH_BATCH_INTERVAL_MS;
    }
}
