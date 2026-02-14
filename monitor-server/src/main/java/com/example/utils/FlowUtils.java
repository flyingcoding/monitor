package com.example.utils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 限流通用工具
 * 针对于不同的情况进行限流操作，支持限流升级
 */
@Slf4j
@Component
public class FlowUtils {

    @Resource
    StringRedisTemplate template;

    /**
     * Redis Lua脚本：同一IP在一个周期内累加计数，超过阈值后写入封禁键，确保原子限流。
     */
    private static final String RATE_LIMIT_SCRIPT = """
            local counter = redis.call('INCR', KEYS[1])
            if counter == 1 then
                redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
            end
            if counter > tonumber(ARGV[2]) then
                redis.call('SET', KEYS[2], '', 'EX', tonumber(ARGV[3]))
                return 0
            end
            return 1
            """;

    /**
     * 针对于单次频率限制，请求成功后，在冷却时间内不得再次进行请求，如3秒内不能再次发起请求
     * @param key 键
     * @param blockTime 限制时间
     * @return 是否通过限流检查
     */
    public boolean limitOnceCheck(String key, int blockTime){
        return this.internalCheck(key, 1, blockTime, (overclock) -> false);
    }

    /**
     * 针对于单次频率限制，请求成功后，在冷却时间内不得再次进行请求
     * 如3秒内不能再次发起请求，如果不听劝阻继续发起请求，将限制更长时间
     * @param key 键
     * @param frequency 请求频率
     * @param baseTime 基础限制时间
     * @param upgradeTime 升级限制时间
     * @return 是否通过限流检查
     */
    public boolean limitOnceUpgradeCheck(String key, int frequency, int baseTime, int upgradeTime){
        return this.internalCheck(key, frequency, baseTime, (overclock) -> {
                    if (overclock)
                        template.opsForValue().set(key, "1", upgradeTime, TimeUnit.SECONDS);
                    return false;
                });
    }

    /**
     * 针对于在时间段内多次请求限制，如3秒内限制请求20次，超出频率则封禁一段时间
     * @param counterKey 计数键
     * @param blockKey 封禁键
     * @param blockTime 封禁时间
     * @param frequency 请求频率
     * @param period 计数周期
     * @return 是否通过限流检查
     */
    public boolean limitPeriodCheck(String counterKey, String blockKey, int blockTime, int frequency, int period){
        Long result = template.execute(
                new DefaultRedisScript<>(RATE_LIMIT_SCRIPT, Long.class),
                List.of(counterKey, blockKey),
                String.valueOf(period), String.valueOf(frequency), String.valueOf(blockTime)
        );
        return result != null && result == 1L;
    }

    /**
     * 内部使用请求限制主要逻辑
     * @param key 计数键
     * @param frequency 请求频率
     * @param period 计数周期
     * @param action 限制行为与策略
     * @return 是否通过限流检查
     */
    private boolean internalCheck(String key, int frequency, int period, LimitAction action){
        String count = template.opsForValue().get(key);
        if (count != null) {
            long value = Optional.ofNullable(template.opsForValue().increment(key)).orElse(0L);
            int c = Integer.parseInt(count);
            if(value != c + 1)
                template.expire(key, period, TimeUnit.SECONDS);
            return action.run(value > frequency);
        } else {
            template.opsForValue().set(key, "1", period, TimeUnit.SECONDS);
            return true;
        }
    }

    /**
     * 内部使用，限制行为与策略
     */
    private interface LimitAction {
        boolean run(boolean overclock);
    }
}
