package com.example.tsdb;

import com.example.entity.vo.request.RuntimeDetailVO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * {@link VictoriaMetricsProvider} stub 单元测试。
 *
 * <p>v2.0-alpha 仅占位；每个方法必须抛 {@link UnsupportedOperationException}，
 * 错误消息含 {@code v2.0-alpha} 字样以便排查。
 */
class VictoriaMetricsProviderTest {

    private final VictoriaMetricsProvider provider = new VictoriaMetricsProvider();
    private final RuntimeDetailVO vo = new RuntimeDetailVO();

    @Test
    void writeRuntimeShouldThrowNotImplemented() {
        UnsupportedOperationException ex = Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> provider.writeRuntime(1, vo));
        Assertions.assertTrue(ex.getMessage().contains("v2.0-alpha"),
                "未实现异常需说明 v2.0-alpha：" + ex.getMessage());
        Assertions.assertTrue(ex.getMessage().contains("writeRuntime"),
                "异常消息应包含方法名");
    }

    @Test
    void writeOtlpMetricShouldThrowNotImplemented() {
        UnsupportedOperationException ex = Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> provider.writeOtlpMetric(1, vo));
        Assertions.assertTrue(ex.getMessage().contains("writeOtlpMetric"));
    }

    @Test
    void readRuntimeHistoryShouldThrowNotImplemented() {
        UnsupportedOperationException ex = Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> provider.readRuntimeHistory(1));
        Assertions.assertTrue(ex.getMessage().contains("readRuntimeHistory"));
    }

    @Test
    void readAvailabilityBucketsShouldThrowNotImplemented() {
        UnsupportedOperationException ex = Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> provider.readAvailabilityBuckets(1));
        Assertions.assertTrue(ex.getMessage().contains("readAvailabilityBuckets"));
    }
}
