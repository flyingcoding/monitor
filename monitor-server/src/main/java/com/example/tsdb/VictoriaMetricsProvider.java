package com.example.tsdb;

import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.entity.vo.response.RuntimeHistoryVO;
import lombok.extern.slf4j.Slf4j;

/**
 * VictoriaMetrics 时序后端占位实现 (v2.0-alpha skeleton)。
 *
 * <p>本类仅用于占据 {@link TimeSeriesAdapter} 接口位、声明未来 v2.0-beta 落地范围；
 * 所有方法抛 {@link UnsupportedOperationException}。
 *
 * <p>{@link TsdbAdapterFactory} 在配置 {@code monitor.tsdb.provider=victoria-metrics} 时
 * 不会直接装配此实现，而是 WARN 后回落到 {@link InfluxDbProvider}（决策 D7：fallback 优先级
 * 高于 fail-fast，避免静默部署中断）。本类提供供未来直连测试或调试时手动注入的入口。
 */
@Slf4j
public class VictoriaMetricsProvider implements TimeSeriesAdapter {

    /**
     * 创建占位实例并打印一次 WARN，便于在日志中确认意图。
     */
    public VictoriaMetricsProvider() {
        log.warn("VictoriaMetricsProvider 实例化为占位实现；v2.0-alpha 未提供真实写入/查询，beta 阶段补齐");
    }

    private static UnsupportedOperationException notImplemented(String operation) {
        return new UnsupportedOperationException(
                "VictoriaMetricsProvider." + operation + " 在 v2.0-alpha 未实现，请使用 InfluxDbProvider 或等待 v2.0-beta");
    }

    @Override
    public void writeRuntime(int clientId, RuntimeDetailVO vo) {
        throw notImplemented("writeRuntime");
    }

    @Override
    public void writeOtlpMetric(int clientId, RuntimeDetailVO vo) {
        throw notImplemented("writeOtlpMetric");
    }

    @Override
    public RuntimeHistoryVO readRuntimeHistory(int clientId) {
        throw notImplemented("readRuntimeHistory");
    }

    @Override
    public double[] readAvailabilityBuckets(int clientId) {
        throw notImplemented("readAvailabilityBuckets");
    }
}
