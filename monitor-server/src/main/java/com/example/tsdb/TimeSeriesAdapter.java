package com.example.tsdb;

import com.example.entity.vo.request.RuntimeDetailVO;
import com.example.entity.vo.response.RuntimeHistoryVO;

import java.time.Instant;

/**
 * 时序数据库适配层 (v2.0-alpha)。
 *
 * <p>把原本散落在 {@code InfluxDbUtils} 中的写入与查询逻辑抽象到统一接口，
 * 让上层服务（{@code ClientService}、{@code StatusPageService}）与具体后端解耦。
 * v2.0-alpha 落地默认实现 {@link InfluxDbProvider}；v2.0-beta 将补 {@code VictoriaMetricsProvider}。
 *
 * <h3>线程模型</h3>
 * <p>实现需要保证 {@link #writeRuntime} 与 {@link #writeOtlpMetric} 可在并发调度线程下安全调用；
 * 读方法（{@link #readRuntimeHistory} / {@link #readAvailabilityBuckets}）只读，对实现无并发约束。
 *
 * <h3>降级与重放</h3>
 * <p>写入失败的降级（断路器 / 本地缓冲 / 调度重放）由实现内部封装，调用方不感知；
 * 见 {@link InfluxDbProvider} 的 Resilience4j {@code @CircuitBreaker(name="tsdb")} 与 JSONL 缓冲。
 */
public interface TimeSeriesAdapter {

    /**
     * 写入客户端直传的运行时指标。
     *
     * <p>对应 {@code /monitor/runtime} 与 {@code /monitor/runtime/batch} 端点的下游写入路径，
     * 必须保留断路器 + 本地缓冲降级行为；调用方不需要 catch 异常。
     *
     * @param clientId 客户端 ID
     * @param vo       运行时指标 VO，单位约定见 {@link RuntimeDetailVO} 注释
     */
    void writeRuntime(int clientId, RuntimeDetailVO vo);

    /**
     * 写入 OTLP HTTP 端点解析后的指标。
     *
     * <p>v2.0-alpha 内部与 {@link #writeRuntime} 走相同的 {@code runtime} measurement，
     * 但保留独立 entry point，便于 v2.0 正式版分流到 {@code otlp_metrics} measurement 或
     * 增加来源 tag，而不影响客户端直传路径。
     *
     * @param clientId 通过 X-Monitor-Token 解析得到的客户端 ID
     * @param vo       从 OTLP {@code monitor.client.*} 白名单映射出的运行时 VO
     */
    void writeOtlpMetric(int clientId, RuntimeDetailVO vo);

    /**
     * 按时间范围查询客户端运行时历史。
     *
     * <p>实现按 {@link TsdbQueryUtils#chooseStep(java.time.Duration)} 选择服务端聚合 step，
     * 使返回点数稳定在 1k-2k 区间（详见 PRD §D4）；前端图表无需感知 provider 切换。
     * 查询失败会以异常向上抛出；上层（{@code ClientServiceImpl.clientRuntimeDetailsHistory}）
     * 决定降级策略。
     *
     * @param clientId 客户端 ID
     * @param from     查询起始时间（含），不允许为 {@code null}
     * @param to       查询截止时间（含），不允许为 {@code null}，且必须晚于 {@code from}
     * @return 历史运行时序列；无数据时返回字段填充为空集合的 VO，不返回 {@code null}
     */
    RuntimeHistoryVO readRuntimeHistory(int clientId, Instant from, Instant to);

    /**
     * 查询客户端 24 小时按 30 分钟切分的可用率桶。
     *
     * <p>每个桶若存在任何上报记录视为 1.0（在线），否则 0.0（离线）。
     * 数组长度恒为 48，oldest → newest。
     *
     * <p>查询异常向上抛出，公开状态页 {@code StatusPageServiceImpl.computeSummary} 会捕获
     * 并对单个 client 用 {@code null} 兜底而不让整页 500。
     *
     * @param clientId 客户端 ID
     * @return 48 个桶的可用率数组；InfluxDB 完全没有数据时返回长度为 0 的空数组
     */
    double[] readAvailabilityBuckets(int clientId);
}
