package com.wuling.common.redis;

import com.wuling.common.security.LoginAttemptGuard;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * 登录失败锁定配置（P2 安全加固）。
 *
 * <p>使用 Redis 作为计数存储：多实例部署下计数共享，
 * 避免攻击者轮询不同实例绕过单机计数。
 *
 * <p>存储位置：{@link RedisNamespace#SMS} 命名空间（该命名空间定位即「验证码与限流计数」）。
 */
@Configuration
public class LoginGuardConfig {

    @Bean
    public LoginAttemptGuard loginAttemptGuard(RedisManager redisManager) {
        StringRedisTemplate redis = redisManager.template(RedisNamespace.SMS);
        return new LoginAttemptGuard(new RedisStore(redis));
    }

    /**
     * 基于 Redis 的计数存储。
     *
     * 注意：写入前统一补上 {@link RedisNamespace#SMS} 前缀。
     * 漏掉前缀会导致 key 落到命名空间之外（与 SMS 等模块混在裸 key 空间），
     * 既破坏按前缀隔离与清理的约定，也可能与其他服务同名 key 冲突。
     */
    static class RedisStore implements LoginAttemptGuard.Store {

        private final StringRedisTemplate redis;

        RedisStore(StringRedisTemplate redis) {
            this.redis = redis;
        }

        /** 补命名空间前缀，保证与 SMS 等模块同一套 key 规范 */
        private String namespaced(String key) {
            return RedisNamespace.SMS.key(key);
        }

        @Override
        public long increment(String key, Duration ttl) {
            String k = namespaced(key);
            Long value = redis.opsForValue().increment(k);
            if (value != null && value == 1L) {
                redis.expire(k, ttl);
            }
            return value == null ? 1L : value;
        }

        @Override
        public void set(String key, String value, Duration ttl) {
            redis.opsForValue().set(namespaced(key), value, ttl);
        }

        @Override
        public Long ttl(String key) {
            return redis.getExpire(namespaced(key), java.util.concurrent.TimeUnit.SECONDS);
        }

        @Override
        public void delete(String key) {
            redis.delete(namespaced(key));
        }
    }
}
