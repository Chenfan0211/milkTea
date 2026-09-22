package com.wuling.common.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis 多库访问器。
 *
 * 使用方式：
 * <pre>
 *   redisManager.template(RedisNamespace.SMS).opsForValue().set(...)
 *   // 或
 *   redisManager.key(RedisNamespace.AUTH, "session:" + userId)
 * </pre>
 *
 * 实现要点：
 * - single 模式：为每个用到的库号缓存一个 StringRedisTemplate（各自持有独立连接工厂）；
 * - cluster 模式：全部返回 db0 的模板，库号配置自动失效，避免集群下 SELECT 报错。
 */
@Component
public class RedisManager {

    private static final Logger log = LoggerFactory.getLogger(RedisManager.class);

    private final RedisConnectionFactory baseFactory;
    private final RedisProperties properties;
    private final Map<Integer, StringRedisTemplate> templates = new ConcurrentHashMap<>();

    public RedisManager(RedisConnectionFactory baseFactory, RedisProperties properties) {
        this.baseFactory = baseFactory;
        this.properties = properties;
        log.info("Redis mode={} databaseMapping={}",
                properties.getMode(), properties.getDatabase());
    }

    /** 按命名空间取模板 */
    public StringRedisTemplate template(RedisNamespace namespace) {
        return templateForDatabase(properties.databaseOf(namespace));
    }

    /** 按库号取模板（缓存复用） */
    public StringRedisTemplate templateForDatabase(int database) {
        return templates.computeIfAbsent(database, this::createTemplate);
    }

    /** 拼接带命名空间前缀的 key */
    public String key(RedisNamespace namespace, String suffix) {
        return namespace.key(suffix);
    }

    public boolean isSingleMode() {
        return properties.isSingleMode();
    }

    private StringRedisTemplate createTemplate(int database) {
        RedisConnectionFactory factory = baseFactory;
        if (properties.isSingleMode() && baseFactory instanceof LettuceConnectionFactory lettuce) {
            // 为不同库号创建独立连接工厂（Lettuce 的连接与库号绑定）
            LettuceConnectionFactory clone = new LettuceConnectionFactory(
                    lettuce.getStandaloneConfiguration(), lettuce.getClientConfiguration());
            clone.setDatabase(database);
            clone.setShareNativeConnection(false);
            clone.afterPropertiesSet();
            factory = clone;
            log.info("Redis template created for database {}", database);
        }
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }
}
