package org.monitorclient;

import org.monitorclient.config.ServerConfiguration;
import org.monitorclient.entity.ConnectionConfig;
import org.monitorclient.task.MonitorScheduler;
import org.monitorclient.utils.MonitorUtils;
import org.monitorclient.utils.NetUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;

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

        log.info("正在向服务端更新基础信息...");
        net.updateBaseDetails(monitor.monitorBaseDetail());

        MonitorScheduler scheduler = new MonitorScheduler(monitor, net);
        scheduler.start();

        CountDownLatch keepAlive = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
            log.info("收到关闭信号，正在优雅退出...");
            scheduler.stop();
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
}
