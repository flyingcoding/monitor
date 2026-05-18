package com.example.service;

import com.example.service.impl.ProbeHistoryCleanupJob;
import com.example.service.impl.ProbeServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link ProbeHistoryCleanupJob} 单元测试。
 *
 * <p>验证：cutoff 取当前时间 - 30 天；委托 {@link ProbeServiceImpl#deleteHistoryBefore(Date)}。
 */
class ProbeHistoryCleanupJobTest {

    private ProbeHistoryCleanupJob job;
    private final AtomicReference<Date> capturedCutoff = new AtomicReference<>();
    private int returnDeleted = 7;

    @BeforeEach
    void setUp() {
        capturedCutoff.set(null);
        job = new ProbeHistoryCleanupJob();
        ProbeServiceImpl probeService = new ProbeServiceImpl() {
            @Override
            public int deleteHistoryBefore(Date cutoff) {
                capturedCutoff.set(cutoff);
                return returnDeleted;
            }
        };
        ReflectionTestUtils.setField(job, "probeService", probeService);
    }

    @Test
    void shouldPassCutoffAt30DaysAgo() {
        Date before = new Date();
        int affected = job.runCleanup();
        Date after = new Date();

        Assertions.assertEquals(returnDeleted, affected);
        Date cutoff = capturedCutoff.get();
        Assertions.assertNotNull(cutoff);

        long lowerMs = before.toInstant().minus(31, ChronoUnit.DAYS).toEpochMilli();
        long upperMs = after.toInstant().minus(29, ChronoUnit.DAYS).toEpochMilli();
        Assertions.assertTrue(cutoff.getTime() >= lowerMs && cutoff.getTime() <= upperMs,
                "cutoff 应在 [now-31d, now-29d] 区间内，实际 " + cutoff);
    }

    @Test
    void shouldReturnZeroIfServiceThrows() {
        ProbeServiceImpl boom = new ProbeServiceImpl() {
            @Override
            public int deleteHistoryBefore(Date cutoff) {
                throw new RuntimeException("simulated DB down");
            }
        };
        ReflectionTestUtils.setField(job, "probeService", boom);
        Assertions.assertEquals(0, job.runCleanup());
    }

    @Test
    void retentionDaysShouldBe30() {
        Assertions.assertEquals(30, ProbeHistoryCleanupJob.RETENTION_DAYS);
    }

    @Test
    void scheduledShouldNotThrow() {
        // 直接调用 @Scheduled 方法（绕过定时器），验证默认实现链路通畅。
        Assertions.assertDoesNotThrow(() -> job.cleanup());
        Assertions.assertNotNull(capturedCutoff.get());
        // 当前实例的最新 cutoff 应在合理窗口内
        Date cutoff = capturedCutoff.get();
        long now = Instant.now().toEpochMilli();
        long expectedMs = now - 30L * 24L * 60L * 60L * 1000L;
        Assertions.assertTrue(Math.abs(cutoff.getTime() - expectedMs) < 5L * 60L * 1000L,
                "cutoff 与预期相差不应超过 5 分钟");
    }
}
