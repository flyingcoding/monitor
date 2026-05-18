package com.example.service.impl;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * 探测历史 30 天滚动清理 job。
 *
 * <p>每日凌晨 3 点执行（{@code @Scheduled(cron="0 0 3 * * *")}）；删除 {@code executed_at < 30 天前} 的所有行。
 */
@Slf4j
@Component
public class ProbeHistoryCleanupJob {

    /** 保留天数。 */
    public static final int RETENTION_DAYS = 30;

    @Resource
    private ProbeServiceImpl probeService;

    /**
     * 凌晨 3 点滚动清理。
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanup() {
        runCleanup();
    }

    /**
     * 公开供测试 / 手动调用的执行入口。
     *
     * @return 被删除的行数
     */
    public int runCleanup() {
        try {
            Date cutoff = Date.from(Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS));
            int affected = probeService.deleteHistoryBefore(cutoff);
            log.info("ProbeHistoryCleanupJob 完成 cutoff={} 删除行数={}", cutoff, affected);
            return affected;
        } catch (Exception e) {
            log.warn("ProbeHistoryCleanupJob 执行异常：{}", e.getMessage());
            return 0;
        }
    }
}
