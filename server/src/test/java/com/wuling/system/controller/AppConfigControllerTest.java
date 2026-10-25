package com.wuling.system.controller;

import com.wuling.common.cache.AppConfigCacheService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 小程序配置接口：首页主视觉与“我的”页轮播必须按约定下发。
 */
class AppConfigControllerTest {

    @Test
    @DisplayName("首页接口下发 home_hero 配置")
    void homeIncludesHeroConfig() {
        AppConfigCacheService cache = mock(AppConfigCacheService.class);
        Map<String, Object> hero = Map.of(
                "imageUrl", "/api/v1/files/public/abc123.jpg",
                "altText", "后台配置的首页活动图");
        when(cache.readValue("home_hero")).thenReturn(hero);

        AppConfigController controller = new AppConfigController(cache, mock(JdbcTemplate.class));

        assertEquals(hero, controller.home().getData().get("hero"),
                "首页接口必须把 home_hero 原样下发给小程序");
    }

    @Test
    @DisplayName("未配置首页主视觉时返回 null，不阻断其他配置")
    void homeHeroCanBeMissing() {
        AppConfigCacheService cache = mock(AppConfigCacheService.class);
        when(cache.readValue("home_hero")).thenReturn(null);

        AppConfigController controller = new AppConfigController(cache, mock(JdbcTemplate.class));

        assertNull(controller.home().getData().get("hero"),
                "未配置主视觉时 hero 必须为 null，由小程序回退静态图");
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
    }
}