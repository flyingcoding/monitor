package org.monitorclient.runtime;

import org.monitorclient.task.MonitorScheduler;
import org.monitorclient.utils.LocalCacheUtils;
import org.monitorclient.utils.NetUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;

/** Owns a fixed number of daemon workers, a bounded shutdown and a monotonic stall watchdog. */
public final class AgentRuntime implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);
    private final MonitorScheduler collector;
    private final NetUtils net;
    private final ScheduledExecutorService reporter;
    private final ScheduledExecutorService watchdog;
    private final LongSupplier clock;
    private final IntConsumer halt;
    private final long stallNanos;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile long reportProgress;
    private long lastSummary;

    /** Creates one transport worker and one watchdog; never spawns replacement workers for stuck tasks. */
    public AgentRuntime(MonitorScheduler collector, NetUtils net, int stallSeconds) {
        this(collector, net, stallSeconds, System::nanoTime, status -> Runtime.getRuntime().halt(status));
    }

    /** Injects time and fatal-exit behavior so watchdog decisions can be tested without terminating the JVM. */
    AgentRuntime(MonitorScheduler collector, NetUtils net, int stallSeconds, LongSupplier clock, IntConsumer halt) {
        if (stallSeconds < 180 || stallSeconds > 3600) throw new IllegalArgumentException("Watchdog timeout must be 180..3600 seconds");
        this.collector = collector;
        this.net = net;
        this.clock = clock;
        this.halt = halt;
        this.stallNanos = TimeUnit.SECONDS.toNanos(stallSeconds);
        this.reportProgress = clock.getAsLong();
        this.lastSummary = reportProgress;
        reporter = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "monitor-reporter"));
        watchdog = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "monitor-watchdog"));
    }

    /** Starts independent collection and transport without fixed-rate catch-up bursts. */
    public void start(int intervalSeconds) {
        collector.start();
        reporter.scheduleWithFixedDelay(this::report, 0, intervalSeconds, TimeUnit.SECONDS);
        watchdog.scheduleWithFixedDelay(this::checkHealth, 30, 30, TimeUnit.SECONDS);
    }

    /** Isolates report failures while recording liveness independently of server availability. */
    private void report() {
        try { net.flushCachedData(); }
        catch (Exception error) { log.warn("Transport worker failed: {}", error.getClass().getSimpleName()); }
        finally { reportProgress = clock.getAsLong(); }
    }

    /** Restarts only stalled workers, not an agent that is simply disconnected from its server. */
    void checkHealth() {
        if (closed.get()) return;
        long now = clock.getAsLong();
        if (now - reportProgress > stallNanos || now - collector.lastProgressNanos() > stallNanos) {
            // Do not invoke hooks or synchronous console logging when native I/O may hold locks.
            log.error("Agent worker stalled; exiting for supervisor restart");
            halt.accept(1);
            return;
        }
        if (now - lastSummary >= TimeUnit.MINUTES.toNanos(5)) {
            lastSummary = now;
            log.info("Agent alive; queued={}, dropped={}", LocalCacheUtils.size(), LocalCacheUtils.droppedCount());
        }
    }

    /** Stops accepting work and spends at most a few seconds on joins and an offline notice. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        watchdog.shutdownNow();
        net.close();
        reporter.shutdownNow();
        collector.stop();
        try {
            if (reporter.awaitTermination(2, TimeUnit.SECONDS)) {
                Thread offline = daemon(net::notifyShutdown, "monitor-offline");
                offline.start();
                offline.join(2000);
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    /** Creates workers that cannot pin the JVM after a bounded shutdown. */
    private static Thread daemon(Runnable action, String name) {
        Thread thread = new Thread(action, name);
        thread.setDaemon(true);
        return thread;
    }
}
