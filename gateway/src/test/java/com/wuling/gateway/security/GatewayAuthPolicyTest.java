package com.wuling.gateway.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关鉴权策略测试。
 *
 * <p>重点验证「fail-safe」：未登记的小程序接口必须默认要求登录，
 * 避免新增接口忘记登记时被意外公开。
 */
class GatewayAuthPolicyTest {

    private final GatewayAuthPolicy policy = new GatewayAuthPolicy();

    // ---------- 公开路径 ----------

    @Test
    void publicEndpointsShouldNotRequireAuth() {
        assertFalse(policy.requiresAuth("/auth/login"));
        assertFalse(policy.requiresAuth("/auth/refreshToken"));
        assertFalse(policy.requiresAuth("/api/v1/app/auth/wx-login"));
        assertFalse(policy.requiresAuth("/api/v1/app/store-types"));
        assertFalse(policy.requiresAuth("/api/v1/app/menu"));
        assertFalse(policy.requiresAuth("/api/v1/app/stores"));
        assertFalse(policy.requiresAuth("/api/v1/app/products/classic-001"));
        assertFalse(policy.requiresAuth("/actuator/health"));
    }

    @Test
    void paymentCallbackShouldNotRequireJwt() {
        // 回调改由签名校验保护，不是 JWT
        assertFalse(policy.requiresAuth("/api/v1/app/payments/callback"));
    }

    // ---------- 受保护路径 ----------

    @Test
    void userEndpointsShouldRequireAuth() {
        assertTrue(policy.requiresAuth("/api/v1/app/orders"));
        assertTrue(policy.requiresAuth("/api/v1/app/orders/WX123"));
        assertTrue(policy.requiresAuth("/api/v1/app/users/1"));
        assertTrue(policy.requiresAuth("/api/v1/app/withdrawals"));
        assertTrue(policy.requiresAuth("/api/v1/app/points/records"));
        assertTrue(policy.requiresAuth("/api/v1/app/points/signin"));
        assertTrue(policy.requiresAuth("/api/v1/app/comments"));
        assertTrue(policy.requiresAuth("/api/v1/app/workbench/subject/1/overview"));
        assertTrue(policy.requiresAuth("/api/v1/app/auth/me"));
        assertTrue(policy.requiresAuth("/api/v1/app/auth/sms/send"));
    }

    @Test
    void adminEndpointsShouldRequireAuth() {
        assertTrue(policy.requiresAuth("/api/v1/admin/crud/orders"));
        assertTrue(policy.requiresAuth("/api/v1/admin/auth/roles"));
        assertTrue(policy.requiresAuth("/api/v1/admin/finance/withdrawals"));
    }

    // ---------- fail-safe 行为 ----------

    @Test
    void unregisteredAppEndpointShouldRequireAuthByDefault() {
        // 这是关键设计：未登记的 app 接口默认要求登录，
        // 漏登记只会「多拦一次」，不会意外公开。
        assertTrue(policy.requiresAuth("/api/v1/app/brand-new-endpoint"));
        assertTrue(policy.requiresAuth("/api/v1/app/whatever/thing"));
    }

    @Test
    void protectedTakesPrecedenceOverPublicPrefix() {
        // /api/v1/app/points/records 受保护，而 /api/v1/app/points/products 公开；
        // 前缀有重叠时必须让受保护清单优先命中，否则会被误放行。
        assertTrue(policy.requiresAuth("/api/v1/app/points/records"));
        assertFalse(policy.requiresAuth("/api/v1/app/points/products"));
    }

    @Test
    void fileEndpointsShouldHaveCorrectPolicy() {
        // 自述端点公开，上传必须登录（防匿名上传恶意文件）
        assertFalse(policy.requiresAuth("/api/v1/files/service-info"));
        assertTrue(policy.requiresAuth("/api/v1/files/validate"));
    }

    @Test
    void nullPathShouldRequireAuth() {
        assertTrue(policy.requiresAuth(null), "路径缺失时按需要鉴权处理");
    }

    @Test
    void otherPathsShouldBeAllowed() {
        // 网关自身端点等非业务路径放行
        assertFalse(policy.requiresAuth("/internal/service-info"));
    }
}
