package org.monitorclient.task;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.monitorclient.entity.BaseDetail;
import org.monitorclient.entity.Capabilities;
import org.monitorclient.entity.RuntimeDetail;
import org.monitorclient.system.MetricCollector;
import org.monitorclient.utils.MonitorUtils;
import org.monitorclient.utils.NetUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class MonitorScheduler {

    private final MonitorUtils monitor;
    private final NetUtils net;
    private final List<MetricCollector> collectors;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * 构造监控调度器，使用 Java 21 Virtual Thread 作为调度线程。
     *
     * @param monitor 监控采集工具
     * @param net 网络上报工具
     */
    public MonitorScheduler(MonitorUtils monitor, NetUtils net) {
        this(monitor, net, List.of());
    }

    /**
     * v1.3 构造：注入 {@link MetricCollector} 列表，启动后每周期由每个 collector 注入自己的聚合指标。
     *
     * @param monitor 监控采集工具
     * @param net 网络上报工具
     * @param collectors v1.3 可选采集器（process / gpu / smart / systemd），允许空列表表示无附加采集
     */
    public MonitorScheduler(MonitorUtils monitor, NetUtils net, List<MetricCollector> collectors) {
        this.monitor = monitor;
        this.net = net;
        this.collectors = collectors == null ? List.of() : List.copyOf(collectors);
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
        log.info("启动监控调度器，每10秒采集一次数据（v1.3 collectors={}）", collectors.size());
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
     * 构建并返回 BaseDetail，附带 capabilities JSON（由各 collector 的 describe 汇总）。
     * 在 {@code MonitorClientApplication} 启动时调用一次。
     *
     * @return 含 capabilitiesJson 的 BaseDetail
     */
    public BaseDetail describeWithCapabilities() {
        BaseDetail base = monitor.monitorBaseDetail();
        if (collectors.isEmpty()) {
            return base;
        }
        Capabilities cap = new Capabilities();
        for (MetricCollector c : collectors) {
            Capabilities.Module module = c.describe();
            switch (c.name()) {
                case "gpu" -> cap.setGpu(module);
                case "smart" -> cap.setSmart(module);
                case "systemd" -> cap.setSystemd(module);
                case "process" -> cap.setProcess(module);
                default -> log.warn("未知 MetricCollector name={}，已忽略", c.name());
            }
        }
        base.setCapabilitiesJson(JSON.toJSONString(cap));
        return base;
    }

    /**
     * 单次采集并上报运行时数据，采集失败时发送心跳维持在线状态。
     * v1.3 在基础 RuntimeDetail 完成后，依次让每个 collector 注入自己的聚合指标。
     */
    private void collectAndReport() {
        try {
            RuntimeDetail runtimeDetail = monitor.monitorRuntimeDetail();
            if (runtimeDetail == null) {
                log.warn("运行时数据采集失败，发送心跳包维持在线状态");
                net.sendHeartbeat();
                return;
            }
            for (MetricCollector c : collectors) {
                try {
                    c.enhance(runtimeDetail);
                } catch (Exception e) {
                    log.warn("MetricCollector {} 执行异常：{}", c.name(), e.getMessage());
                }
            }
            net.updateRuntimeDetails(runtimeDetail);
        } catch (Exception e) {
            log.error("监控数据采集上报异常", e);
        }
    }

    List<MetricCollector> collectorsForTest() {
        return new ArrayList<>(collectors);
    }
}
