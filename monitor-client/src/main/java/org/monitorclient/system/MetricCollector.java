package org.monitorclient.system;

import org.monitorclient.entity.Capabilities;
import org.monitorclient.entity.RuntimeDetail;

/**
 * v1.3 客户端可选指标采集器抽象。
 * <p>
 * Phase 1 各模块（Process / GPU / SMART / systemd）各实现一个 {@link MetricCollector}：
 * <ul>
 *   <li>{@link #describe()} 在客户端启动时被调用，生成 {@link Capabilities.Module}，参与 BaseDetail 上报；</li>
 *   <li>{@link #enhance(RuntimeDetail)} 在每个 10s 采集周期被 {@code MonitorScheduler} 调用，
 *       把自己的聚合指标（最高 GPU 温度 / SMART 关键异常数 / failed systemd 数 / 进程缺失数）
 *       注入 {@link RuntimeDetail}。</li>
 * </ul>
 * <p>
 * Collector 实现需做到：
 * <ol>
 *   <li>构造时探测能力，如对应工具缺失则在 {@link #describe()} 返回 {@code available=false}，
 *       且 {@link #enhance(RuntimeDetail)} 早退不写字段（保持 null）。</li>
 *   <li>{@link #enhance(RuntimeDetail)} 单次执行预算 ≤ 1s；超时由具体执行器自己处理。</li>
 *   <li>本接口与 {@link CommandExecutor} / {@link SystemInfoProvider} 解耦，便于注入 mock 写单测。</li>
 * </ol>
 */
public interface MetricCollector {
    /**
     * 返回模块名（用于 Capabilities JSON 的 key：gpu / smart / systemd / process）。
     *
     * @return 模块名
     */
    String name();

    /**
     * 描述本采集器的能力快照（启动时调用一次，参与 BaseDetail 上报）。
     *
     * @return 能力 Module，若 enabled=false 则不被启用
     */
    Capabilities.Module describe();

    /**
     * 把当前周期采集到的聚合指标写入 RuntimeDetail（每 10s 调用一次）。
     * 异常应吞掉并打 warn 日志，不应中断整个采集周期。
     *
     * @param runtime 本周期 RuntimeDetail，由调用方先用 MonitorUtils 填充基础字段
     */
    void enhance(RuntimeDetail runtime);
}
