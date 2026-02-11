package org.monitorclient.task;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.monitorclient.entity.RuntimeDetail;
import org.monitorclient.utils.MonitorUtils;
import org.monitorclient.utils.NetUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class MonitorScheduler implements CommandLineRunner, DisposableBean {

    @Resource
    MonitorUtils monitor;
    @Resource
    NetUtils net;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "monitor-scheduler");
        t.setDaemon(false);
        return t;
    });

    @Override
    public void run(String... args) {
        log.info("启动监控调度器，每10秒采集一次数据");
        scheduler.scheduleAtFixedRate(this::collectAndReport, 10, 10, TimeUnit.SECONDS);
    }

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

    @Override
    public void destroy() {
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
        net.notifyShutdown();
        log.info("监控调度器已关闭");
    }
}
