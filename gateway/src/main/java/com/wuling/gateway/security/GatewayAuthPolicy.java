package com.wuling.gateway.security;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 网关鉴权策略：判定某路径是否需要登录。
 *
 * <p>设计依据：与单体 {@code WebConfig} 中 {@code MiniAppAuthInterceptor} 的保护清单保持一致，
 * 拆分期二者需同步维护，否则会出现「网关放行、下游也放行」的鉴权空档。
 *
 * <p><b>采用「默认需要鉴权 + 白名单放行」而非「默认放行 + 黑名单拦截」</b>：
 * 后者一旦新增接口忘记登记就会默认暴露；前者漏登记的后果是「多拦一次」，
 * 属于可立即发现的安全失败（fail-safe）。
 */
@Component
public class GatewayAuthPolicy {

    /** 完全公开的路径前缀（无需登录） */
    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/auth/login",
            "/auth/refreshToken",
            "/api/v1/app/auth/wx-login",
            // 未注册用户建号/发验证码（无需 JWT，注册凭证由 Redis 校验）
            "/api/v1/app/auth/sms/send",
            "/api/v1/app/auth/register-by-sms",
            "/api/v1/app/auth/register-by-phone",
            // 公开业务数据：门店、菜单、商品、字典
            "/api/v1/app/store-types",
            "/api/v1/app/stores",
            "/api/v1/app/menu",
            "/api/v1/app/products",
            "/api/v1/app/coupons",
            // 第 5 期：营销服务的公开接口（储值套餐、礼品卡面额、会员等级；
            // 其余营销接口如订单/积分记录/提现仍需登录，见 PROTECTED）
            "/api/v1/app/points/products",
            "/api/v1/app/stored-value/packages",
            "/api/v1/app/gift-cards/denominations",
            "/api/v1/app/member-levels",
            "/api/v1/app/points/rules",
            "/api/v1/app/signin-rule",
            "/api/v1/app/referral-config",
            "/api/v1/app/config",
            "/api/v1/app/cities",
            // 支付回调：由签名校验保护，非 JWT
            "/api/v1/app/payments/callback",
            // 微信支付结果通知（第 14 期）：调用方是微信服务器，无 JWT。
            // 保护手段为「RSA 验签 + AES-GCM 解密 + 金额比对 + 时间戳窗口」，
            // 且该接口仅在 app.pay.channel=wxpay 时注册（mock 阶段不存在）。
            "/api/v1/app/payments/wxpay/notify",
            // 微信退款结果通知：同样由 RSA 验签 + AES-GCM 解密保护，调用方无 JWT。
            "/api/v1/app/payments/wxpay/refund-notify",
            // 健康检查
            "/actuator/health",
            // 文件服务公开读取（文件名由服务端生成并严格校验）
            "/api/v1/files/public/**",
            // 文件服务自述（不含敏感信息，便于运维探活）
            "/api/v1/files/service-info"
    );

    /** 需要登录的精确路径（仅该路径本身，不包含子路径） */
    private static final List<String> PROTECTED_EXACT_PATHS = List.of(
            "/api/v1/app/gift-cards"
    );

    private static final List<String> PROTECTED_PREFIXES = List.of(
            "/api/v1/app/orders",
            "/api/v1/app/users",
            "/api/v1/app/stored-value/orders",
            "/api/v1/app/stored-value/orders/**",
            // 储值支付发起（第 15 期）：需登录，由服务端按 JWT 取 openid
            "/api/v1/app/payments/stored-value/prepay",
            // 礼品卡支付与退款：需登录，服务端按 JWT 校验订单归属并读取 openid
            "/api/v1/app/payments/gift-card/prepay",
            "/api/v1/app/payments/gift-card/refund",
            // 礼品卡购买、订单与核销需登录；面额列表 /denominations 公开，
            // 因此不能使用宽泛的 /api/v1/app/gift-cards 保护前缀。
            "/api/v1/app/gift-cards/orders",
            "/api/v1/app/gift-cards/verify",
            "/api/v1/app/gift-cards/purchase",
            "/api/v1/app/points/records",
            "/api/v1/app/points/signin",
            "/api/v1/app/points/signin-dates",
            "/api/v1/app/points/exchange",
            "/api/v1/app/points/exchange-orders",
            "/api/v1/app/withdrawals",
            "/api/v1/app/workbench",
            "/api/v1/app/roles",
            "/api/v1/app/auth/me",
            "/api/v1/app/auth/sms/bind",
            "/api/v1/app/auth/avatar",
            "/api/v1/app/auth/nickname",
            "/api/v1/app/auth/phone",
            "/api/v1/app/auth/profile",
            "/api/v1/app/auth/location",
            // 文件上传：需登录（防匿名上传恶意文件）
            "/api/v1/files/images",
            "/api/v1/files/validate"
    );

    /**
     * 是否需要登录。
     *
     * <p>判定顺序：先看受保护清单（优先命中），再看公开清单，最后按前缀兜底：
     * <ul>
     *   <li>{@code /api/v1/admin/**} → 需要管理端登录；</li>
     *   <li>{@code /api/v1/app/**} → 默认需要登录（fail-safe）；</li>
     *   <li>其他 → 放行（如网关自身端点）。</li>
     * </ul>
     */
    public boolean requiresAuth(String path) {
        if (path == null) {
            return true;
        }
        // 精确保护路径优先；用于保护 /gift-cards 本身，同时放行其公开子路径。
        if (PROTECTED_EXACT_PATHS.contains(path)) {
            return true;
        }
        // 受保护优先：避免某个路径同时被两条清单前缀匹配时被误放行
        for (String p : PROTECTED_PREFIXES) {
            if (matchesPrefix(path, p)) {
                return true;
            }
        }
        for (String p : PUBLIC_PREFIXES) {
            if (matchesPrefix(path, p)) {
                return false;
            }
        }
        if (path.startsWith("/api/v1/admin/")) {
            return true;
        }
        if (path.startsWith("/api/v1/app/")) {
            // 未登记的 app 接口：默认要求登录（fail-safe）
            return true;
        }
        return false;
    }

    /**
     * 将清单中的路径前缀按“精确路径或子路径”匹配，支持 {@code /**} 写法。
     */
    private static boolean matchesPrefix(String path, String configuredPrefix) {
        String prefix = configuredPrefix.endsWith("/**")
                ? configuredPrefix.substring(0, configuredPrefix.length() - 3)
                : configuredPrefix;
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
