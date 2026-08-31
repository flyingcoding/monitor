package org.monitorclient.runtime;

import org.junit.jupiter.api.Test;
import org.monitorclient.task.MonitorScheduler;
import org.monitorclient.utils.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Checks watchdog decisions without actually halting or starting a runtime. */
class AgentRuntimeTest {
    /** Drains all four fresh snapshot slots before the original server's short cache expires. */
    @Test
    void shouldPumpSnapshotsIndependentlyOfCollectionInterval() throws Exception {
        java.util.concurrent.CountDownLatch sent = new java.util.concurrent.CountDownLatch(4);
        NetUtils net = new NetUtils() {
            /** Acknowledges snapshots without opening a network connection. */
            @Override protected org.monitorclient.entity.Response request(String method, String path, String json) {
                if (!"/offline".equals(path)) sent.countDown();
                return new org.monitorclient.entity.Response(200, null, "fixture");
            }
        };
        MonitorScheduler collector = new MonitorScheduler(new MonitorUtils(), net) {
            /** Keeps this test independent from actual hardware collection. */
            @Override public void start() { }
        };
        net.postGpuSnapshot(java.util.Collections.emptyList());
        net.postSmartSnapshot(java.util.Collections.emptyList());
        net.postSystemdSnapshot(java.util.Collections.emptyList());
        net.postProcessSnapshot(new org.monitorclient.collector.ProcessSnapshot());
        try (AgentRuntime runtime = new AgentRuntime(collector, net, 300)) {
            runtime.start(10);
            assertTrue(sent.await(7, java.util.concurrent.TimeUnit.SECONDS), "snapshot delivery must not wait four collection intervals");
        }
    }

    /** Exits only on stalled local workers, allowing ordinary server outages to keep buffering. */
    @Test
    void shouldDetectStallsUsingMonotonicProgress() {
        AtomicLong clock = new AtomicLong(1);
        AtomicInteger exit = new AtomicInteger(-1);
        MonitorScheduler scheduler = new MonitorScheduler(new MonitorUtils(), new NetUtils()) {
            /** Supplies deterministic collection progress without touching OSHI. */
            @Override public long lastProgressNanos() { return 1; }
        };
        try (AgentRuntime runtime = new AgentRuntime(scheduler, new NetUtils(), 300, clock::get, exit::set)) {
            clock.addAndGet(TimeUnit.SECONDS.toNanos(299));
            runtime.checkHealth();
            assertEquals(-1, exit.get());
            clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
            runtime.checkHealth();
            assertEquals(1, exit.get());
        }
    }
}
