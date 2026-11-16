package com.wuling.system.controller;

import com.wuling.common.cache.AppConfigCacheService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * 小程序配置接口：首页主视觉与“我的”页轮播必须按约定下发。
 */
class AppConfigControllerTest {

    @Test
    @DisplayName("首页只下发启用且未删除的轮播，保留旧客户端单图字段")
    void homeIncludesEnabledBanners() {
        AppConfigCacheService cache = mock(AppConfigCacheService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        List<Map<String, Object>> banners = List.of(Map.of(
                "id", 8L, "imageUrl", "/api/v1/files/public/abc123.jpg", "altText", "后台配置的首页活动图",
                "targetType", "coupons", "targetId", ""));
        when(jdbcTemplate.queryForList(contains("from home_banner"))).thenReturn(banners);

        AppConfigController controller = new AppConfigController(cache, jdbcTemplate);
        Map<?, ?> hero = (Map<?, ?>) controller.home().getData().get("hero");

        assertEquals(banners, hero.get("banners"));
        assertEquals("/api/v1/files/public/abc123.jpg", hero.get("imageUrl"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture());
        assertTrue(sql.getValue().contains("status = 'enabled'"));
        assertTrue(sql.getValue().contains("deleted = 0"));
        assertTrue(sql.getValue().contains("order by sort asc, id asc"));
    }

    @Test
    @DisplayName("没有可用首页轮播时返回空列表和无跳转默认图")
    void homeFallsBackToDefaultBanner() {
        AppConfigCacheService cache = mock(AppConfigCacheService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(contains("from home_banner"))).thenReturn(List.of());
        AppConfigController controller = new AppConfigController(cache, jdbcTemplate);
        Map<?, ?> hero = (Map<?, ?>) controller.home().getData().get("hero");
        assertEquals(List.of(), hero.get("banners"));
        assertEquals("/assets/images/3x/home-hero.jpg", hero.get("imageUrl"));
        assertEquals("none", hero.get("targetType"));
    }

    @Test
    @DisplayName("我的页接口下发启用且排序后的轮播")
    void profileIncludesEnabledBanners() {
        AppConfigCacheService cache = mock(AppConfigCacheService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        List<Map<String, Object>> banners = List.of(
                Map.of("id", 1L, "imageUrl", "/api/v1/files/public/one.jpg", "altText", "第一张"),
                Map.of("id", 2L, "imageUrl", "/api/v1/files/public/two.jpg", "altText", "第二张"));
        when(jdbcTemplate.queryForList(contains("from profile_banner"))).thenReturn(banners);

        AppConfigController controller = new AppConfigController(cache, jdbcTemplate);

        assertEquals(banners, controller.profile().getData().get("banners"),
                "我的页接口必须返回启用且按 sort/id 排序的轮播");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture());
        org.junit.jupiter.api.Assertions.assertTrue(sql.getValue().contains("target_type as targetType"),
                "我的页轮播必须下发跳转类型");
        org.junit.jupiter.api.Assertions.assertTrue(sql.getValue().contains("target_id as targetId"),
                "我的页轮播必须下发商品 ID");
    }
}
