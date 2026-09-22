package com.wuling.marketing.config;

import com.wuling.security.MiniAppAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 营销服务鉴权拦截器注册（第 5 期）。
 *
 * <p>与单体 server 的 {@code WebConfig} 保持同一模型：
 * <ul>
 *   <li>Spring Security 放行 {@code /api/v1/app/**}（外层不拦）；</li>
 *   <li>由 {@link MiniAppAuthInterceptor} 按路径清单精确拦截需要登录的接口。</li>
 * </ul>
 *
 * <p><b>为什么不用 Security 的 requestMatchers 精确控制</b>：
 * 与单体保持一致，避免同一套接口在不同服务里出现两种鉴权语义，
 * 减少拆分期的行为差异。
 *
 * <p>公开接口（无需登录）：优惠券模板、储值套餐、礼品卡面额、
 * 积分商品与规则、会员等级。
 */
@Configuration
public class MarketingSecurityConfig implements WebMvcConfigurer {

    private final MiniAppAuthInterceptor miniAppAuthInterceptor;

    public MarketingSecurityConfig(MiniAppAuthInterceptor miniAppAuthInterceptor) {
        this.miniAppAuthInterceptor = miniAppAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(miniAppAuthInterceptor)
                .addPathPatterns(
                        // 用户优惠券
                        "/api/v1/app/users/**",
                        // 储值：订单与充值需登录（套餐列表公开）
                        "/api/v1/app/stored-value/orders",
                        "/api/v1/app/stored-value/recharge",
                        // 礼品卡：我的卡与购买需登录（面额列表公开）
                        "/api/v1/app/gift-cards",
                        "/api/v1/app/gift-cards/purchase",
                        // 时光币：记录/签到/兑换需登录（商品与规则公开）
                        "/api/v1/app/points/records",
                        "/api/v1/app/points/signin",
                        "/api/v1/app/points/exchange",
                        "/api/v1/app/points/exchange-orders",
                        // 评论
                        "/api/v1/app/comments"
                );
    }
}
