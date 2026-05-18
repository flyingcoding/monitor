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
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 客户端启动入口，负责串联配置加载、基础信息上报、调度启动与优雅停机。
 */
public class MonitorClientApplication {

    private static final Logger log = LoggerFactory.getLogger(MonitorClientApplication.class);
    private static final String COLLECTOR_CONFIG_FILE = "application.properties";

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
     * 优先读取外部 {@code config/application.properties} / {@code application.properties}
     * （当前工作目录与 JAR 所在目录），缺失时再读 classpath 下的同名文件。
     *
     * @return Properties 实例（永不为 null）
     */
    static Properties loadCollectorProperties() {
        return loadCollectorProperties(collectorConfigCandidates(), MonitorClientApplication.class.getClassLoader());
    }

    /**
     * 按候选路径加载 collector 配置，外部文件缺失时回退到 classpath。
     *
     * @param externalPaths 外部配置候选路径，按优先级排序
     * @param classLoader   classpath 资源加载器
     * @return Properties 实例（永不为 null）
     */
    static Properties loadCollectorProperties(List<Path> externalPaths, ClassLoader classLoader) {
        Properties props = new Properties();
        for (Path path : externalPaths) {
            if (path == null || !Files.isRegularFile(path)) {
                continue;
            }
            try (InputStream is = Files.newInputStream(path)) {
                props.load(is);
                log.info("已加载 collector 配置文件：{}", path.toAbsolutePath().normalize());
                return props;
            } catch (IOException e) {
                log.warn("加载 collector 配置文件失败 path={}, reason={}",
                        path.toAbsolutePath().normalize(), e.getMessage());
            }
        }

        ClassLoader effectiveClassLoader = classLoader == null
                ? MonitorClientApplication.class.getClassLoader() : classLoader;
        try (InputStream is = effectiveClassLoader.getResourceAsStream(COLLECTOR_CONFIG_FILE)) {
            if (is != null) {
                props.load(is);
                log.info("已加载 classpath collector 配置：{}", COLLECTOR_CONFIG_FILE);
            }
        } catch (IOException e) {
            log.warn("加载 classpath collector 配置失败，所有可选采集禁用：{}", e.getMessage());
        }
        return props;
    }

    /**
     * 返回 collector 配置候选路径，当前工作目录优先，随后是 JAR 所在目录。
     *
     * @return 去重后的配置路径列表
     */
    private static List<Path> collectorConfigCandidates() {
        Set<Path> paths = new LinkedHashSet<>();
        addCollectorConfigCandidates(paths, Path.of("").toAbsolutePath().normalize());
        resolveApplicationDirectory().ifPresent(path -> addCollectorConfigCandidates(paths, path));
        return new ArrayList<>(paths);
    }

    /**
     * 向候选集合添加指定目录下的 collector 配置文件路径。
     *
     * @param paths   候选集合
     * @param baseDir 基础目录
     */
    private static void addCollectorConfigCandidates(Set<Path> paths, Path baseDir) {
        if (baseDir == null) {
            return;
        }
        paths.add(baseDir.resolve("config").resolve(COLLECTOR_CONFIG_FILE).normalize());
        paths.add(baseDir.resolve(COLLECTOR_CONFIG_FILE).normalize());
    }

    /**
     * 解析当前应用所在目录；以 JAR 方式部署时用于读取同级外部配置。
     *
     * @return 应用目录，解析失败时为空
     */
    private static Optional<Path> resolveApplicationDirectory() {
        try {
            var codeSource = MonitorClientApplication.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return Optional.empty();
            }
            Path location = Path.of(codeSource.getLocation().toURI()).toAbsolutePath().normalize();
            return Optional.of(Files.isRegularFile(location) ? location.getParent() : location);
        } catch (URISyntaxException | RuntimeException e) {
            log.debug("解析应用目录失败：{}", e.getMessage());
            return Optional.empty();
        }
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
