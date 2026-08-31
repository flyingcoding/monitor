package org.monitorclient.utils;

import org.junit.jupiter.api.*;
import org.monitorclient.entity.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises bounded queues, failure backoff and per-round limits without sleeping or network I/O. */
class ReportStabilityTest {
    /** Gives each test an empty process-local queue. */
    @BeforeEach @AfterEach
    void clearQueue() { while (!LocalCacheUtils.isEmpty()) LocalCacheUtils.drainBatch(); }

    /** Continues accepting fresh samples while the server is offline, retaining at most 1000. */
    @Test
    void shouldBoundOfflineMemoryAndPreferFreshSamples() {
        AtomicLong time = new AtomicLong(1);
        FakeNet net = new FakeNet(time);
        net.status = 503;
        for (int i = 1; i <= 1100; i++) net.updateRuntimeDetails(sample(i));
        assertEquals(0, net.calls.size(), "collection must not perform network I/O");
        assertEquals(1000, LocalCacheUtils.size());
        net.flushCachedData();
        assertEquals(1, net.calls.size());
        assertEquals(1000, LocalCacheUtils.size(), "failed batch must be put back");
        net.flushCachedData();
        assertEquals(1, net.calls.size(), "backoff must skip attempts without sleeping");
        List<RuntimeDetail> batch = LocalCacheUtils.drainBatch();
        assertEquals(1100, batch.get(0).getTimestamp());
        assertEquals(50, batch.size());
    }

    /** Keeps collection that arrived during an in-flight replay when rolling back a full queue. */
    @Test
    void shouldNeverEvictNewSamplesToRestoreOldReplay() {
        for (int i = 1; i <= 1000; i++) LocalCacheUtils.offer(sample(i));
        List<RuntimeDetail> pending = LocalCacheUtils.drainBatch();
        for (int i = 1001; i <= 1100; i++) LocalCacheUtils.offer(sample(i));
        LocalCacheUtils.requeueUnsentBatch(pending, 0);
        assertEquals(1000, LocalCacheUtils.size());
        assertEquals(1100, LocalCacheUtils.drainBatch().get(0).getTimestamp());
    }

    /** Limits recovery traffic to one batch and treats credential errors as a five-minute cooldown. */
    @Test
    void shouldBoundRecoveryAndAuthenticationRetries() {
        AtomicLong time = new AtomicLong(1);
        FakeNet net = new FakeNet(time);
        net.status = 401;
        for (int i = 1; i <= 100; i++) net.updateRuntimeDetails(sample(i));
        net.flushCachedData();
        time.addAndGet(TimeUnit.SECONDS.toNanos(299));
        net.flushCachedData();
        assertEquals(1, net.calls.size());
        time.addAndGet(TimeUnit.SECONDS.toNanos(1));
        net.status = 200;
        net.flushCachedData();
        assertEquals(2, net.calls.size());
        assertEquals(50, LocalCacheUtils.size());
        assertEquals("/runtime/batch", net.calls.get(1));
        java.util.List<RuntimeDetail> sent = com.alibaba.fastjson2.JSON.parseArray(net.lastBody, RuntimeDetail.class);
        assertEquals(100, sent.get(sent.size() - 1).getTimestamp(), "original server must see the newest sample last");
    }

    /** Keeps only the latest snapshot per kind and sends at most one per transport round. */
    @Test
    void shouldCoalesceSnapshotsAndAvoidPoisonedMetadataBlockingMetrics() {
        AtomicLong time = new AtomicLong(1);
        FakeNet net = new FakeNet(time);
        net.updateBaseDetails(new BaseDetail());
        net.postGpuSnapshot(Collections.emptyList());
        net.postGpuSnapshot(Collections.emptyList());
        net.postSmartSnapshot(Collections.emptyList());
        net.updateRuntimeDetails(sample(1));
        net.flushCachedData();
        assertEquals(Arrays.asList("/runtime/batch", "/detail", "/gpu"), net.calls);
        net.calls.clear();
        net.flushCachedData();
        assertEquals(Collections.singletonList("/smart"), net.calls);
    }

    /** Creates small scalar samples, with time as the deduplication key. */
    private RuntimeDetail sample(long timestamp) { return new RuntimeDetail().setTimestamp(timestamp); }

    /** Records attempts while allowing deterministic endpoint failures. */
    private static final class FakeNet extends NetUtils {
        private final List<String> calls = new ArrayList<>();
        private int status = 200;
        private String lastBody;
        /** Uses a fake monotonic clock for immediate cooldown tests. */
        private FakeNet(AtomicLong clock) { super(clock::get); }
        /** Simulates a protocol response without opening sockets. */
        @Override
        protected Response request(String method, String path, String json) {
            calls.add(path);
            lastBody = json;
            return new Response(status, null, "fixture");
        }
    }
}
