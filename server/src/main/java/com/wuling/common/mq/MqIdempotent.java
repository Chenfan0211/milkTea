package com.wuling.common.mq;

import com.wuling.common.redis.RedisManager;
import com.wuling.common.redis.RedisNamespace;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 消息幂等消费组件。
 *
 * 背景：MQ 只保证 at-least-once，同一消息可能被投递多次（重试、ACK 丢失、网络抖动）。
 * 做法：消费前用 Redis SETNX 占位，已存在说明处理过，直接跳过。
 *
 * 注意：占位在业务执行【前】写入，因此业务失败时需由调用方决定是否释放，
 * 否则会在 TTL 内阻止重试。见 {@link #release}。
 */
@Component
public class MqIdempotent {

    /** 幂等键前缀 */
    private static final String KEY_PREFIX = "consumed:";

    /** 默认保留 24 小时，覆盖重试窗口 */
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final RedisManager redisManager;

    public MqIdempotent(RedisManager redisManager) {
        this.redisManager = redisManager;
    }

    private StringRedisTemplate redis() {
        // 幂等标记属于业务数据，放 BIZ 命名空间
        return redisManager.template(RedisNamespace.BIZ);
    }

    /**
     * 尝试占位。
     *
     * @param messageId 消息唯一 ID
     * @return true=首次处理，可继续；false=已处理过，应跳过
     */
    public boolean tryAcquire(String messageId) {
        return tryAcquire(messageId, DEFAULT_TTL);
    }

    public boolean tryAcquire(String messageId, Duration ttl) {
        if (!StringUtils.hasText(messageId)) {
            // 无 messageId 时不做幂等（无法判定），退化为每次处理
            return true;
        }
        String key = RedisNamespace.BIZ.key(KEY_PREFIX + messageId);
        Boolean ok = redis().opsForValue().setIfAbsent(key, "1", ttl);
        return Boolean.TRUE.equals(ok);
    }

    /**
     * 释放占位（业务失败时调用，允许后续重试）。
     */
    public void release(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            return;
        }
        redis().delete(RedisNamespace.BIZ.key(KEY_PREFIX + messageId));
    }
}
