package com.wuling.system.cache;

import com.wuling.common.redis.RedisManager;
import com.wuling.common.redis.RedisNamespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * 失效点单菜单缓存。
 *
 * <p>菜单由 product-service 写入 {@code wuling:cache:menu:all} 与
 * {@code wuling:cache:menu:store:*}，TTL 约 5 分钟。分类保存在 server 的通用 CRUD，
 * 与商品编辑不在同一个进程，写完分类若不删这些 key，点单页会继续返回旧标签、旧排序和旧启停状态。
 *
 * <p>生产 Redis 禁用 KEYS，门店 key 必须用 SCAN。删除失败不影响保存，TTL 仍会兜底。
 */
@Service
public class MenuCacheEvictor {

    private static final Logger log = LoggerFactory.getLogger(MenuCacheEvictor.class);

    /** 与 product-service {@code ProductQueryService} 的菜单缓存 key 保持一致。 */
    private static final String MENU_CACHE_KEY = "menu:all";
    private static final String STORE_MENU_CACHE_PREFIX = "menu:store:";

    private final RedisManager redisManager;

    public MenuCacheEvictor(RedisManager redisManager) {
        this.redisManager = redisManager;
    }

    public void evict() {
        try {
            StringRedisTemplate redis = redisManager.template(RedisNamespace.CACHE);
            redis.delete(redisManager.key(RedisNamespace.CACHE, MENU_CACHE_KEY));
            deleteByPattern(redis, redisManager.key(RedisNamespace.CACHE, STORE_MENU_CACHE_PREFIX + "*"));
        } catch (Exception e) {
            log.warn("菜单缓存失效失败，交给 TTL: {}", e.getMessage());
        }
    }

    private void deleteByPattern(StringRedisTemplate redis, String pattern) {
        Set<String> batch = new HashSet<>();
        try {
            redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
                ScanOptions options = ScanOptions.scanOptions().match(pattern).count(500).build();
                try (Cursor<byte[]> cursor = connection.scan(options)) {
                    while (cursor.hasNext()) {
                        batch.add(new String(cursor.next(), StandardCharsets.UTF_8));
                    }
                } catch (Exception e) {
                    log.warn("扫描菜单缓存 key 中断，已收集部分交给删除，其余交给 TTL: {}", e.getMessage());
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("菜单缓存 SCAN 不可用，交给 TTL: {}", e.getMessage());
            return;
        }
        if (!batch.isEmpty()) {
            redis.delete(batch);
        }
    }
}
