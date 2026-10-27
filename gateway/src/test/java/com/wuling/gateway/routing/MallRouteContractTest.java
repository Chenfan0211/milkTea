package com.wuling.gateway.routing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商城路由契约测试。
 *
 * <p>背景：网关生产环境使用宿主机外部配置（/opt/wuling/config/gateway）。
 * 若外部配置缺少商城路径，请求会落到 legacy-server 的 /api/v1/** 兜底，
 * 表现为 404「资源不存在」或 500，而小程序只看到模糊错误。
 *
 * <p>本测试锁住仓库默认配置里必须存在的商城路由，避免下次发布回退。
 */
class MallRouteContractTest {

    private String config() throws IOException {
        return Files.readString(Path.of("src/main/resources/application.yml"));
    }

    @Test
    void mallPublicRoutesMustExist() throws IOException {
        String text = config();
        assertTrue(text.contains("/api/v1/app/mall/categories"), "缺少商城分类公开路由");
        assertTrue(text.contains("/api/v1/app/mall/products/**"), "缺少商城商品公开路由");
        assertTrue(text.contains("/api/v1/app/mall/skus/validate"), "缺少 SKU 校验公开路由");
    }

    @Test
    void mallProtectedRoutesMustExist() throws IOException {
        String text = config();
        assertTrue(text.contains("/api/v1/app/mall/orders"), "缺少商城下单/订单路由");
        assertTrue(text.contains("/api/v1/app/mall/after-sales"), "缺少商城售后路由");
        assertTrue(text.contains("/api/v1/app/addresses"), "缺少收货地址路由");
    }
}
