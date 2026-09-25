package com.wuling.trade.config;

import com.wuling.security.MiniAppAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 交易服务鉴权拦截器注册（第 7 期）。
 *
 * <p>与 server 的 {@code WebConfig} 保持同一模型：Spring Security 外层放行
 * {@code /api/v1/app/**}，由本拦截器按路径清单精确拦截需登录的接口。
 *
 * <p>当前保护的接口：订单列表/详情/下单/支付，门店核销（读 + 写）。
 * <b>注意</b>：与 server、marketing 的清单需保持同步（网关侧另有
 * {@code GatewayAuthPolicy}）。已由 {@code AuthPolicyConsistencyTest} 做一致性校验。
 */
@Configuration
public class TradeSecurityConfig implements WebMvcConfigurer {

    private final MiniAppAuthInterceptor miniAppAuthInterceptor;

    public TradeSecurityConfig(MiniAppAuthInterceptor miniAppAuthInterceptor) {
        this.miniAppAuthInterceptor = miniAppAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(miniAppAuthInterceptor)
                .addPathPatterns(
                        "/api/v1/app/orders",
                        "/api/v1/app/orders/**",
                        // 门店核销（读 + 写）：必须登录并校验门店归属
                        "/api/v1/app/workbench/store/*/verify-records",
                        "/api/v1/app/workbench/store/*/verify-pool",
                        "/api/v1/app/workbench/store/*/verify",
                        "/api/v1/app/workbench/store-operator/check"
                );
        // 说明：/api/v1/app/payments/callback 不在此列 ——
                // 它是第三方支付回调，由 HMAC 签名校验保护，不走用户 JWT。
    }
}
