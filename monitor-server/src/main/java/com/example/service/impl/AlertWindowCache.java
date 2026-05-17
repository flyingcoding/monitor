package com.example.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * 告警评估滑动窗口缓存。
 * <p>
 * 每个 {@code (ruleId, clientId)} 维护一个 {@link Deque} of {@link EvalSample}，
 * 记录最近的评估结果与时间戳。窗口在
 * {@link #record(Long, Integer, boolean, int)} 时按 {@code durationSec * 2}
 * 自动裁剪（保留略大于持续时间的样本以判断"持续满足"）。
 * <p>
 * 用 Caffeine 提供 {@code expireAfterAccess=1h} 与上限 10000 个 entry，
 * 防止删除/禁用规则后窗口长期残留。
 * <p>
 * 并发安全：评估方法 {@code AlertEvaluator.evaluate} 跑在 {@code alertTaskExecutor}
 * 虚拟线程池上，同一 {@code (ruleId, clientId)} 在批量补报或高频上报时存在并发访问可能。
 * 本类的 {@code record} / {@code isContinuously*} / {@code clear} 都使用 entry-level
 * {@code synchronized(deque)} 保护内部 {@link ArrayDeque}，对外通过 {@link #lockFor(Long, Integer)}
 * 暴露同一锁对象，供调用方将"查活跃历史 → 写入 history → 投 MQ"段一并串行化。
 */
@Component
public class AlertWindowCache {

    private static final long DEFAULT_EXPIRE_MINUTES = 60L;
    private static final long DEFAULT_MAX_ENTRIES = 10_000L;

    private final Cache<String, Deque<EvalSample>> cache = Caffeine.newBuilder()
            .maximumSize(DEFAULT_MAX_ENTRIES)
            .expireAfterAccess(DEFAULT_EXPIRE_MINUTES, TimeUnit.MINUTES)
            .build();

    /**
     * (ruleId, clientId) → 独立锁对象。仅用于在 {@link AlertEvaluatorImpl} 的"查活跃历史 +
     * 写库 + 投 MQ"段做 entry-level 串行化，避免虚拟线程竞态。
     * <p>
     * 不直接复用 deque 引用作为锁：调用方可能尚未触发 {@link #record} 即先想要拿锁；
     * 同时也避免 Caffeine 失效后锁实例改变导致互斥失效。该 map 不会无限增长，
     * Caffeine 缓存失效后 stale lock 通过 {@code cleanupLockIfAbsent} 在窗口清空时移除。
     */
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    /**
     * 构造窗口缓存 key。
     *
     * @param ruleId   规则ID
     * @param clientId 客户端ID
     * @return 缓存 key
     */
    public static String windowKey(Long ruleId, Integer clientId) {
        return "r" + ruleId + ":c" + clientId;
    }

    /**
     * 追加一个评估样本。会按 {@code durationSec * 2} 秒裁剪过旧样本，保留判断窗口所需余量。
     * 对内部 deque 加 entry-level 锁保护，避免虚拟线程并发 record 时破坏 {@link ArrayDeque} 状态。
     *
     * @param ruleId      规则ID
     * @param clientId    客户端ID
     * @param met         本次评估是否满足阈值条件
     * @param durationSec 规则要求的持续秒数
     */
    public void record(Long ruleId, Integer clientId, boolean met, int durationSec) {
        String key = windowKey(ruleId, clientId);
        Deque<EvalSample> samples = cache.get(key, k -> new ArrayDeque<>());
        synchronized (samples) {
            Instant now = Instant.now();
            samples.addLast(new EvalSample(now, met));
            long retainMillis = Math.max(durationSec, 1) * 2L * 1000L;
            Instant cutoff = now.minusMillis(retainMillis);
            while (!samples.isEmpty() && samples.peekFirst().ts().isBefore(cutoff)) {
                samples.pollFirst();
            }
        }
    }

    /**
     * 判断窗口内是否持续满足条件：所有样本 met=true，且最早样本时间距今 >= durationSec。
     *
     * @param ruleId      规则ID
     * @param clientId    客户端ID
     * @param durationSec 规则要求的持续秒数
     * @return 持续满足返回 true
     */
    public boolean isContinuouslyMet(Long ruleId, Integer clientId, int durationSec) {
        return isContinuously(ruleId, clientId, durationSec, true);
    }

    /**
     * 判断窗口内是否持续不满足条件：所有样本 met=false，且最早样本时间距今 >= durationSec。
     *
     * @param ruleId      规则ID
     * @param clientId    客户端ID
     * @param durationSec 规则要求的持续秒数
     * @return 持续不满足返回 true
     */
    public boolean isContinuouslyNotMet(Long ruleId, Integer clientId, int durationSec) {
        return isContinuously(ruleId, clientId, durationSec, false);
    }

    /**
     * 移除某条规则的所有窗口缓存与关联锁。
     *
     * @param ruleId   规则ID
     * @param clientId 客户端ID
     */
    public void clear(Long ruleId, Integer clientId) {
        String key = windowKey(ruleId, clientId);
        cache.invalidate(key);
        locks.remove(key);
    }

    /**
     * 获取与该 {@code (ruleId, clientId)} 关联的 entry-level 锁对象。
     * <p>
     * 调用方应在该锁上 {@code synchronized} 串行化"查活跃历史 → 创建 history → 投 MQ"段。
     * 同一对 key 始终返回同一对象，跨调用稳定。
     *
     * @param ruleId   规则ID
     * @param clientId 客户端ID
     * @return entry-level 锁对象
     */
    public Object lockFor(Long ruleId, Integer clientId) {
        return locks.computeIfAbsent(windowKey(ruleId, clientId), k -> new Object());
    }

    /**
     * 评估窗口内样本是否全部为指定的 {@code expected} 状态，且窗口跨度 >= durationSec。
     * 与 {@link #record} 共用 entry-level 锁，遍历过程中不会被并发 record 改写。
     *
     * @param ruleId      规则ID
     * @param clientId    客户端ID
     * @param durationSec 持续秒数
     * @param expected    期望状态
     * @return 满足条件返回 true
     */
    private boolean isContinuously(Long ruleId, Integer clientId, int durationSec, boolean expected) {
        Deque<EvalSample> samples = cache.getIfPresent(windowKey(ruleId, clientId));
        if (samples == null) {
            return false;
        }
        synchronized (samples) {
            if (samples.isEmpty()) {
                return false;
            }
            EvalSample first = samples.peekFirst();
            EvalSample last = samples.peekLast();
            if (first == null || last == null) {
                return false;
            }
            long spanMs = last.ts().toEpochMilli() - first.ts().toEpochMilli();
            if (spanMs < Math.max(durationSec, 1) * 1000L) {
                return false;
            }
            for (EvalSample s : samples) {
                if (s.met() != expected) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * 内部样本结构：时间戳 + 评估结果。
     *
     * @param ts  评估时间
     * @param met 是否满足阈值
     */
    public record EvalSample(Instant ts, boolean met) {
    }
}
