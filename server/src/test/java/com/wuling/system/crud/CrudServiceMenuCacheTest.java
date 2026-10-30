package com.wuling.system.crud;

import com.wuling.common.cache.AppConfigCacheService;
import com.wuling.common.redis.RedisManager;
import com.wuling.common.redis.RedisNamespace;
import com.wuling.system.cache.MenuCacheEvictor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrudServiceMenuCacheTest {

    private JdbcTemplate jdbcTemplate;
    private StringRedisTemplate redis;
    private CrudService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        redis = mock(StringRedisTemplate.class);
        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.template(RedisNamespace.CACHE)).thenReturn(redis);
        when(redisManager.key(any(RedisNamespace.class), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0, RedisNamespace.class).key(invocation.getArgument(1)));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.queryForList(startsWith("select * from product_category"), any(Object[].class)))
                .thenReturn(List.of(categoryRow()));
        when(jdbcTemplate.update(any(PreparedStatementCreator.class), any(KeyHolder.class))).thenAnswer(invocation -> {
            KeyHolder holder = invocation.getArgument(1);
            holder.getKeyList().add(new LinkedHashMap<>(Map.of("id", 1L)));
            return 1;
        });
        service = new CrudService(jdbcTemplate, mock(AppConfigCacheService.class), new MenuCacheEvictor(redisManager));
    }

    @Test
    void updatingProductCategoryDeletesMenuCache() {
        service.update("productCategories", 1L, Map.of("tag", "新品"));

        verify(redis).delete("wuling:cache:menu:all");
        verify(redis).execute(any(RedisCallback.class));
    }

    @Test
    void creatingProductCategoryDeletesMenuCache() {
        service.create("productCategories", Map.of("name", "季节限定", "tag", "上新", "enabled", 1, "sort", 8));

        verify(redis).delete("wuling:cache:menu:all");
    }

    @Test
    void deletingProductCategoryDeletesMenuCache() {
        service.delete("productCategories", 1L);

        verify(redis).delete("wuling:cache:menu:all");
    }

    @Test
    void otherResourcesDoNotDeleteMenuCache() {
        when(jdbcTemplate.queryForList(startsWith("select * from points_category"), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 7L, "code", "coupon", "name", "优惠券区", "sort", 10, "enabled", 1)));

        service.update("pointsCategories", 7L, Map.of("name", "优惠券专区"));

        verify(redis, never()).delete(anyString());
    }

    private static Map<String, Object> categoryRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1L);
        row.put("code", "featured-season");
        row.put("name", "季节限定");
        row.put("tag", "上新");
        row.put("type", "CATEGORY");
        row.put("sort", 8);
        row.put("enabled", 1);
        return row;
    }
}
