package org.monitorclient.task;

import lombok.extern.slf4j.Slf4j;
import org.monitorclient.entity.RuntimeDetail;
import org.monitorclient.utils.MonitorUtils;
import org.monitorclient.utils.NetUtils;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class MonitorScheduler {

    private final MonitorUtils monitor;
    private final NetUtils net;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * 构造监控调度器，使用 Java 21 Virtual Thread 作为调度线程。
     *
     * @param monitor 监控采集工具
     * @param net 网络上报工具
     */
    public MonitorScheduler(MonitorUtils monitor, NetUtils net) {
        this.monitor = monitor;
        this.net = net;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("monitor-scheduler", 1).factory());
    }

    /**
     * 启动定时采集与上报任务。
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        log.info("启动监控调度器，每10秒采集一次数据");
        scheduler.scheduleAtFixedRate(this::collectAndReport, 10, 10, TimeUnit.SECONDS);
    }

    /**
     * 停止调度器并等待任务退出。
     */
    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        log.info("正在关闭监控调度器...");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("监控调度器已关闭");
    }

    /**
     * 单次采集并上报运行时数据，采集失败时发送心跳维持在线状态。
     */
    private void collectAndReport() {
        try {
            RuntimeDetail runtimeDetail = monitor.monitorRuntimeDetail();
            if (runtimeDetail == null) {
                log.warn("运行时数据采集失败，发送心跳包维持在线状态");
                net.sendHeartbeat();
            } else {
                net.updateRuntimeDetails(runtimeDetail);
            }
        } catch (Exception e) {
            log.error("监控数据采集上报异常", e);
        }
    }
}
