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
 * <p>兼容两种模式：投递前占位（BEFORE_HANDLE）和业务成功后标记（AFTER_SUCCESS）。
 * 订阅消息这类“宁可重复一次也不能静默丢消息”的链路必须使用后者。</p>
 */
@Component
public class MqIdempotent {

    private static final String KEY_PREFIX = "consumed:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final RedisManager redisManager;

    public MqIdempotent(RedisManager redisManager) {
        this.redisManager = redisManager;
    }

    private StringRedisTemplate redis() {
        return redisManager.template(RedisNamespace.BIZ);
    }

    /** 尝试占位；仅适用于业务失败时可安全释放的 BEFORE_HANDLE 模式。 */
    public boolean tryAcquire(String messageId) {
        return tryAcquire(messageId, DEFAULT_TTL);
    }

    public boolean tryAcquire(String messageId, Duration ttl) {
        if (!StringUtils.hasText(messageId)) {
            return true;
        }
        String key = RedisNamespace.BIZ.key(KEY_PREFIX + messageId);
        Boolean ok = redis().opsForValue().setIfAbsent(key, "1", ttl);
        return Boolean.TRUE.equals(ok);
    }

    /** 是否已经成功消费。 */
    public boolean isConsumed(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            return false;
        }
        String key = RedisNamespace.BIZ.key(KEY_PREFIX + messageId);
        return Boolean.TRUE.equals(redis().hasKey(key));
    }

    /** 业务成功后再写入幂等标记；失败不得调用。 */
    public void markConsumed(String messageId) {
        markConsumed(messageId, DEFAULT_TTL);
    }

    public void markConsumed(String messageId, Duration ttl) {
        if (!StringUtils.hasText(messageId)) {
            return;
        }
        redis().opsForValue().set(RedisNamespace.BIZ.key(KEY_PREFIX + messageId), "1", ttl);
    }

    public void release(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            return;
        }
        redis().delete(RedisNamespace.BIZ.key(KEY_PREFIX + messageId));
    }
}
