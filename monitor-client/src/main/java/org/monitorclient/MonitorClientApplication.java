package org.monitorclient;

import org.monitorclient.collector.GpuCollector;
import org.monitorclient.collector.ProcessCollector;
import org.monitorclient.collector.SmartCollector;
import org.monitorclient.collector.SystemdCollector;
import org.monitorclient.config.ServerConfiguration;
import org.monitorclient.entity.ConnectionConfig;
import org.monitorclient.system.MetricCollector;
import org.monitorclient.system.OshiSystemInfoProvider;
import org.monitorclient.system.ProcessCommandExecutor;
import org.monitorclient.system.SystemInfoProvider;
import org.monitorclient.task.MonitorScheduler;
import org.monitorclient.utils.MonitorUtils;
import org.monitorclient.utils.NetUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 客户端启动入口，负责串联配置加载、基础信息上报、调度启动与优雅停机。
 */
public class MonitorClientApplication {

    private static final Logger log = LoggerFactory.getLogger(MonitorClientApplication.class);

    /**
     * 客户端主函数。
     *
     * @param args 启动参数，支持 --server / --token
     */
    public static void main(String[] args) {
        log.info("Monitor Client 启动中...");

        NetUtils net = new NetUtils();
        MonitorUtils monitor = new MonitorUtils();
        ServerConfiguration configuration = new ServerConfiguration(net);

        ConnectionConfig connectionConfig = configuration.loadConfig(args);
        if (connectionConfig == null) {
            log.error("未能加载到可用的服务端连接配置，客户端退出。");
            return;
        }
        net.setConfig(connectionConfig);

        // v1.3 Phase 1 各模块在此注册自己的 MetricCollector（ProcessCollector / GpuCollector / SmartCollector / SystemdCollector）。
        List<MetricCollector> collectors = new ArrayList<>();
        Properties collectorProperties = loadCollectorProperties();
        ProcessCommandExecutor commandExecutor = new ProcessCommandExecutor();
        SystemInfoProvider sharedSystemInfo = new OshiSystemInfoProvider();
        ProcessCollector processCollector = new ProcessCollector(sharedSystemInfo, net, collectorProperties);
        collectors.add(processCollector);
        SystemdCollector systemdCollector = new SystemdCollector(commandExecutor, collectorProperties);
        collectors.add(systemdCollector);
        SmartCollector smartCollector = new SmartCollector(commandExecutor, collectorProperties);
        collectors.add(smartCollector);
        GpuCollector gpuCollector = new GpuCollector(commandExecutor, collectorProperties);
        collectors.add(gpuCollector);

        MonitorScheduler scheduler = new MonitorScheduler(monitor, net, collectors);

        log.info("正在向服务端更新基础信息...");
        net.updateBaseDetails(scheduler.describeWithCapabilities());

        scheduler.start();
        ScheduledExecutorService snapshotReporter = startSnapshotReporter(net, systemdCollector, smartCollector, gpuCollector);

        CountDownLatch keepAlive = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
            log.info("收到关闭信号，正在优雅退出...");
            scheduler.stop();
            stopSnapshotReporter(snapshotReporter);
            net.notifyShutdown();
            keepAlive.countDown();
        }));

        try {
            keepAlive.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("主线程等待被中断，客户端退出");
        }
    }

    /**
     * 加载 application.properties 中的 collector 开关与白名单配置。
     * <p>
     * 优先读 classpath 下的 {@code application.properties}；缺失或读取失败时返回空 Properties，
     * 等价于所有可选采集模块禁用。
     *
     * @return Properties 实例（永不为 null）
     */
    static Properties loadCollectorProperties() {
        Properties props = new Properties();
        try (InputStream is = MonitorClientApplication.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException e) {
            log.warn("加载 application.properties 失败，所有可选采集禁用：{}", e.getMessage());
        }
        return props;
    }

    /**
     * 启动详情快照上报线程：每 10 秒读取各 collector 的最新快照并上报到服务端。
     * <p>
     * 详情快照独立于 RuntimeDetail 聚合指标上报通道，前端可单独消费。
     *
     * @param net 网络工具
     * @param systemdCollector systemd 采集器
     * @param smartCollector   SMART 采集器
     * @param gpuCollector     GPU 采集器
     * @return 调度器（用于 shutdown 时停止）
     */
    static ScheduledExecutorService startSnapshotReporter(NetUtils net,
                                                          SystemdCollector systemdCollector,
                                                          SmartCollector smartCollector,
                                                          GpuCollector gpuCollector) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("collector-snapshot-reporter", 1).factory());
        executor.scheduleAtFixedRate(() -> {
            try {
                var snapshot = systemdCollector.snapshot();
                if (!snapshot.isEmpty()) {
                    net.postSystemdSnapshot(snapshot);
                }
            } catch (Exception e) {
                log.warn("systemd 快照上报异常：{}", e.getMessage());
            }
            try {
                var smartSnapshot = smartCollector.lastSnapshot();
                if (!smartSnapshot.isEmpty()) {
                    net.postSmartSnapshot(smartSnapshot);
                }
            } catch (Exception e) {
                log.warn("SMART 快照上报异常：{}", e.getMessage());
            }
            try {
                var gpuSnapshot = gpuCollector.lastSnapshot();
                if (!gpuSnapshot.isEmpty()) {
                    net.postGpuSnapshot(gpuSnapshot);
                }
            } catch (Exception e) {
                log.warn("GPU 快照上报异常：{}", e.getMessage());
            }
        }, 15, 10, TimeUnit.SECONDS);
        return executor;
    }

    /**
     * 停止详情快照上报线程，等待至多 5 秒。
     *
     * @param executor 调度器
     */
    static void stopSnapshotReporter(ScheduledExecutorService executor) {
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
