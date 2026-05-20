package com.example.tsdb;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;

/**
 * {@link TsdbQueryUtils} 单元测试。
 *
 * <p>覆盖 step 选择规则的所有边界值（1h / 6h / 24h / 7d）、超出 7d 的兜底逻辑，
 * 以及 {@code null} / 非正数 window 的防御行为。
 */
class TsdbQueryUtilsTest {

    @Test
    void chooseStepShouldReturn10sForExactlyOneHour() {
        Duration step = TsdbQueryUtils.chooseStep(Duration.ofHours(1));
        Assertions.assertEquals(TsdbQueryUtils.STEP_10S, step,
                "1h 临界点应仍属于 ≤ 1h 区间，返回 10s 原生分辨率");
    }

    @Test
    void chooseStepShouldReturn10sForSmallerWindows() {
        Assertions.assertEquals(TsdbQueryUtils.STEP_10S, TsdbQueryUtils.chooseStep(Duration.ofMinutes(1)));
        Assertions.assertEquals(TsdbQueryUtils.STEP_10S, TsdbQueryUtils.chooseStep(Duration.ofMinutes(30)));
        Assertions.assertEquals(TsdbQueryUtils.STEP_10S, TsdbQueryUtils.chooseStep(Duration.ofSeconds(10)));
    }

    @Test
    void chooseStepShouldReturn30sJustAboveOneHour() {
        Duration step = TsdbQueryUtils.chooseStep(Duration.ofHours(1).plusSeconds(1));
        Assertions.assertEquals(TsdbQueryUtils.STEP_30S, step,
                "1h+1s 应进入 6h 区间，返回 30s");
    }

    @Test
    void chooseStepShouldReturn30sForExactlySixHours() {
        Duration step = TsdbQueryUtils.chooseStep(Duration.ofHours(6));
        Assertions.assertEquals(TsdbQueryUtils.STEP_30S, step, "6h 临界点应仍属于 ≤ 6h 区间，返回 30s");
    }

    @Test
    void chooseStepShouldReturn2MinJustAboveSixHours() {
        Duration step = TsdbQueryUtils.chooseStep(Duration.ofHours(6).plusSeconds(1));
        Assertions.assertEquals(TsdbQueryUtils.STEP_2M, step,
                "6h+1s 应进入 24h 区间，返回 2min");
    }

    @Test
    void chooseStepShouldReturn2MinForExactlyTwentyFourHours() {
        Duration step = TsdbQueryUtils.chooseStep(Duration.ofHours(24));
        Assertions.assertEquals(TsdbQueryUtils.STEP_2M, step, "24h 临界点应仍属于 ≤ 24h 区间，返回 2min");
    }

    @Test
    void chooseStepShouldReturn10MinJustAboveTwentyFourHours() {
        Duration step = TsdbQueryUtils.chooseStep(Duration.ofHours(24).plusSeconds(1));
        Assertions.assertEquals(TsdbQueryUtils.STEP_10M, step,
                "24h+1s 应进入 7d 区间，返回 10min");
    }

    @Test
    void chooseStepShouldReturn10MinForExactlySevenDays() {
        Duration step = TsdbQueryUtils.chooseStep(Duration.ofDays(7));
        Assertions.assertEquals(TsdbQueryUtils.STEP_10M, step, "7d 临界点应仍属于 ≤ 7d 区间，返回 10min");
    }

    @Test
    void chooseStepShouldFallbackForWindowJustAboveSevenDays() {
        // 7d + 1s → seconds = 604801
        // ceil(604801 / 1500) = 404 秒
        Duration step = TsdbQueryUtils.chooseStep(Duration.ofDays(7).plusSeconds(1));
        long expectedSeconds = (Duration.ofDays(7).plusSeconds(1).toSeconds()
                + TsdbQueryUtils.MAX_POINTS_FALLBACK - 1) / TsdbQueryUtils.MAX_POINTS_FALLBACK;
        Assertions.assertEquals(Duration.ofSeconds(expectedSeconds), step,
                "超 7d 应按 ceil(seconds / 1500) 计算");
    }

    @Test
    void chooseStepShouldFallbackForThirtyDayWindowAndStayUnder2000Points() {
        Duration window = Duration.ofDays(30);
        Duration step = TsdbQueryUtils.chooseStep(window);
        long pointCount = window.toSeconds() / step.toSeconds();
        Assertions.assertTrue(pointCount <= 2000,
                "30d 窗口下采样后点数必须 ≤ 2000，实际 " + pointCount);
        Assertions.assertTrue(pointCount >= 1000,
                "30d 窗口下采样后点数应 ≥ 1000，避免视图过于稀疏，实际 " + pointCount);
    }

    @Test
    void chooseStepShouldGuardAgainstNullWindow() {
        Assertions.assertEquals(TsdbQueryUtils.STEP_10S, TsdbQueryUtils.chooseStep(null),
                "null window 视作 1h，返回 10s");
    }

    @Test
    void chooseStepShouldGuardAgainstZeroWindow() {
        Assertions.assertEquals(TsdbQueryUtils.STEP_10S, TsdbQueryUtils.chooseStep(Duration.ZERO),
                "0 window 视作 1h，返回 10s");
    }

    @Test
    void chooseStepShouldGuardAgainstNegativeWindow() {
        Assertions.assertEquals(TsdbQueryUtils.STEP_10S, TsdbQueryUtils.chooseStep(Duration.ofSeconds(-100)),
                "负 window 视作 1h，返回 10s");
    }

    @Test
    void chooseStepShouldNeverReturnNull() {
        // 全部边界都不可返回 null
        Assertions.assertNotNull(TsdbQueryUtils.chooseStep(Duration.ofSeconds(1)));
        Assertions.assertNotNull(TsdbQueryUtils.chooseStep(Duration.ofDays(365)));
    }

    @Test
    void chooseStepShouldNeverReturnZeroOrNegativeStep() {
        // 即使极端大 window，step 也至少 1s
        Duration veryLong = Duration.ofDays(10_000);
        Duration step = TsdbQueryUtils.chooseStep(veryLong);
        Assertions.assertTrue(step.toSeconds() >= 1, "step 必须 ≥ 1s，避免除 0 或无意义 step");
    }
}
