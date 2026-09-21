package com.wuling.common.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.common.redis.RedisManager;
import com.wuling.common.redis.RedisNamespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 小程序运营配置读取（缓存优先）。
 *
 * 读取顺序：
 *   1) 先读 Redis（CACHE 命名空间）；
 *   2) 未命中则回源 MySQL(app_config)；
 *   3) 回源结果写回 Redis，并设置 TTL。
 *
 * 一致性策略：
 *   - TTL 默认 1 个月，作为兜底（即便失效逻辑未触发，旧值也不会长期驻留）；
 *   - 配置更新时调用 {@link #evict(String)} 主动删除缓存，下次读取即回源。
 *
 * 敏感配置：
 *   tencent_map_key 这类密钥仅允许服务端内部读取（{@link #readValue} 结果不会经由
 *   AppConfigController 下发），请在业务层控制，不要把它加到任何对外接口的返回里。
 */
@Service
public class AppConfigCacheService {

    private static final Logger log = LoggerFactory.getLogger(AppConfigCacheService.class);

    /** 缓存键前缀，挂在 CACHE 命名空间下 */
    private static final String CACHE_PREFIX = "app_config:";

    /** 缓存有效期：1 个月 */
    private static final Duration CACHE_TTL = Duration.ofDays(30);

    private final JdbcTemplate jdbcTemplate;
    private final RedisManager redisManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AppConfigCacheService(JdbcTemplate jdbcTemplate, RedisManager redisManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisManager = redisManager;
    }

    /**
     * 读取配置值：缓存优先，未命中回源数据库并写回缓存。
     *
     * @param key app_config.config_key
     * @return 已反序列化的 JSON 结构；不存在时返回 null
     */
    public Object readValue(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }

        String cacheKey = redisManager.key(RedisNamespace.CACHE, CACHE_PREFIX + key);
        StringRedisTemplate template = redisManager.template(RedisNamespace.CACHE);

        // 1) 先读缓存
        String cached = safeGet(template, cacheKey, key);
        if (cached != null) {
            Object parsed = parse(cached);
            if (parsed != null) {
                return parsed;
            }
            // 缓存内容损坏时删除，走回源逻辑，避免持续返回坏数据
            log.warn("配置缓存内容无法解析，已清除: key={}", key);
            safeEvict(template, cacheKey, key);
        }

        // 2) 回源数据库
        Object value = readFromDatabase(key);
        if (value == null) {
            return null;
        }

        // 3) 写回缓存
        safeSet(template, cacheKey, key, value);
        return value;
    }

    /** 读取字符串形态的配置值（适合密钥等纯文本场景） */
    public String readString(String key) {
        Object value = readValue(key);
        if (value == null) {
            return null;
        }
        return value instanceof String text ? text : String.valueOf(value);
    }

    /**
     * 读取对象形态的配置值。
     * 注意：JSON 列读出来是字符串，这里统一转成 Map 便于取字段。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> readMap(String key) {
        Object value = readValue(key);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    /** 主动失效：配置更新后调用，下次读取即回源 */
    public void evict(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        String cacheKey = redisManager.key(RedisNamespace.CACHE, CACHE_PREFIX + key);
        safeEvict(redisManager.template(RedisNamespace.CACHE), cacheKey, key);
    }

    /** 缓存有效期，便于测试与排查 */
    public Duration cacheTtl() {
        return CACHE_TTL;
    }

    // ---------- 内部实现 ----------

    private Object readFromDatabase(String key) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select value from app_config where config_key = ? and status = 'enabled' and deleted = 0",
                key);
        if (rows.isEmpty() || rows.get(0).get("value") == null) {
            return null;
        }
        Object raw = rows.get(0).get("value");
        // MySQL JSON 列经 JdbcTemplate 读出来是 String，需反序列化后返回
        if (raw instanceof String text) {
            Object parsed = parse(text);
            return parsed != null ? parsed : text;
        }
        return raw;
    }

    private Object parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(text, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("配置缓存序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private String safeGet(StringRedisTemplate template, String cacheKey, String key) {
        try {
            return template.opsForValue().get(cacheKey);
        } catch (Exception e) {
            // 缓存不可用不应影响业务，回源数据库即可
            log.warn("读取配置缓存失败，回源数据库: key={} error={}", key, e.getMessage());
            return null;
        }
    }

    private void safeSet(StringRedisTemplate template, String cacheKey, String key, Object value) {
        String text = serialize(value);
        if (text == null) {
            return;
        }
        try {
            template.opsForValue().set(cacheKey, text, CACHE_TTL);
        } catch (Exception e) {
            log.warn("写入配置缓存失败: key={} error={}", key, e.getMessage());
        }
    }

    private void safeEvict(StringRedisTemplate template, String cacheKey, String key) {
        try {
            template.delete(cacheKey);
            log.info("配置缓存已失效: key={}", key);
        } catch (Exception e) {
            log.warn("清除配置缓存失败: key={} error={}", key, e.getMessage());
        }
    }
}