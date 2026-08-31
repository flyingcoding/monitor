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

/**
 * 客户端启动入口，负责串联配置加载、基础信息上报、调度启动与优雅停机。
 */
public class MonitorClientApplication {

    private static final Logger log = LoggerFactory.getLogger(MonitorClientApplication.class);
    private static final java.util.concurrent.atomic.AtomicBoolean LOGGING_STOPPED = new java.util.concurrent.atomic.AtomicBoolean();
    private static final String COLLECTOR_CONFIG_FILE = "application.properties";

    /**
     * 客户端主函数。
     *
     * @param args 启动参数，支持 --server / --token
     */
    public static void main(String[] args) {
        verifyLogDirectory();
        log.info("Monitor Client 启动中...");

        Properties collectorProperties = loadCollectorProperties();
        int intervalSeconds = reportIntervalSeconds(collectorProperties);
        int stallSeconds = Integer.parseInt(collectorProperties.getProperty("monitor.watchdog.timeout-seconds", "300"));
        if (stallSeconds < 180 || stallSeconds > 3600) throw new IllegalArgumentException("Watchdog timeout must be 180..3600 seconds");
        NetUtils net = new NetUtils();
        ConnectionConfig connectionConfig = new ServerConfiguration(net).loadConfig(args);
        net.setConfig(connectionConfig);
        SystemInfoProvider sharedSystemInfo = new OshiSystemInfoProvider();
        MonitorUtils monitor = new MonitorUtils(sharedSystemInfo, System.getProperties());
        ProcessCommandExecutor commandExecutor = new ProcessCommandExecutor();
        List<MetricCollector> collectors = new ArrayList<>();
        collectors.add(new ProcessCollector(sharedSystemInfo, net, collectorProperties));
        collectors.add(new SystemdCollector(commandExecutor, collectorProperties));
        collectors.add(new SmartCollector(commandExecutor, collectorProperties));
        collectors.add(new GpuCollector(commandExecutor, collectorProperties));
        MonitorScheduler scheduler = new MonitorScheduler(monitor, net, collectors, intervalSeconds);
        org.monitorclient.runtime.AgentRuntime runtime = new org.monitorclient.runtime.AgentRuntime(scheduler, net, stallSeconds);
        CountDownLatch keepAlive = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            runtime.close();
            stopLogging();
            keepAlive.countDown();
        }, "monitor-shutdown"));
        try {
            runtime.start(intervalSeconds);
            keepAlive.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            runtime.close();
            stopLogging();
        }
    }

    /** Flushes the bounded async log queue without allowing a stuck disk to pin JVM shutdown. */
    private static void stopLogging() {
        if (!LOGGING_STOPPED.compareAndSet(false, true)) return;
        Thread flush = new Thread(() -> {
            org.slf4j.ILoggerFactory factory = LoggerFactory.getILoggerFactory();
            if (factory instanceof ch.qos.logback.classic.LoggerContext) {
                ((ch.qos.logback.classic.LoggerContext) factory).stop();
            }
        }, "monitor-log-stop");
        flush.setDaemon(true);
        flush.start();
        try { flush.join(2000); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    /** Fails visibly if the default persistent log directory cannot be used. */
    private static void verifyLogDirectory() {
        String directory = System.getProperty("MONITOR_LOG_DIR", System.getenv("MONITOR_LOG_DIR"));
        Path path = java.nio.file.Paths.get(directory == null ? "logs" : directory);
        try {
            Files.createDirectories(path);
            if (!Files.isWritable(path)) throw new IOException("Log directory is not writable");
        } catch (IOException error) {
            throw new IllegalStateException("Cannot write monitor log directory; check MONITOR_LOG_DIR and service user permissions");
        }
    }

    /** Loads and validates the shared runtime/snapshot interval in seconds. */
    static int reportIntervalSeconds(Properties properties) {
        String configured = System.getProperty("monitor.report.interval-seconds",
                properties.getProperty("monitor.report.interval-seconds", "10"));
        int interval = Integer.parseInt(configured.trim());
        if (interval < 1 || interval > 60) throw new IllegalArgumentException("monitor.report.interval-seconds must be between 1 and 60");
        return interval;
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
                if (Files.size(path) > 65536) throw new IllegalArgumentException("Collector configuration exceeds 64 KiB");
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
        addCollectorConfigCandidates(paths, java.nio.file.Paths.get("").toAbsolutePath().normalize());
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
            java.security.CodeSource codeSource = MonitorClientApplication.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return Optional.empty();
            }
            Path location = java.nio.file.Paths.get(codeSource.getLocation().toURI()).toAbsolutePath().normalize();
            return Optional.of(Files.isRegularFile(location) ? location.getParent() : location);
        } catch (URISyntaxException | RuntimeException e) {
            log.debug("解析应用目录失败：{}", e.getMessage());
            return Optional.empty();
        }
    }

}
