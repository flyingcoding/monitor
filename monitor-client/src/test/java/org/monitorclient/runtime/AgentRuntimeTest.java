package org.monitorclient.runtime;

import org.junit.jupiter.api.Test;
import org.monitorclient.task.MonitorScheduler;
import org.monitorclient.utils.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Checks watchdog decisions without actually halting or starting a runtime. */
class AgentRuntimeTest {
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
