package com.wuling.common.config;

import com.wuling.security.MiniAppAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 小程序端鉴权拦截器注册。
 *
 * 保护范围（仅用户相关）：
 * - 订单与支付、用户资料与优惠券、储值订单/充值、我的礼品卡与购买
 * - 时光币（记录/签到/兑换）、提现、评论、角色工作台
 * - 账号操作：me / sms / avatar / nickname / phone / profile / location
 *
 * 公开接口（无需登录）：门店、菜单、商品、门店类型、优惠券模板、会员等级、wx-login。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final MiniAppAuthInterceptor miniAppAuthInterceptor;

    public WebConfig(MiniAppAuthInterceptor miniAppAuthInterceptor) {
        this.miniAppAuthInterceptor = miniAppAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(miniAppAuthInterceptor)
                .addPathPatterns(
                        "/api/v1/app/orders",
                        "/api/v1/app/orders/**",
                        "/api/v1/app/users/**",
                        "/api/v1/app/stored-value/orders",
                        "/api/v1/app/stored-value/recharge",
                        "/api/v1/app/gift-cards",
                        "/api/v1/app/gift-cards/purchase",
                        "/api/v1/app/points/records",
                        "/api/v1/app/points/signin",
                        "/api/v1/app/points/exchange",
                        "/api/v1/app/points/exchange-orders",
                        "/api/v1/app/withdrawals",
                        "/api/v1/app/withdrawals/**",
                        "/api/v1/app/comments",
                        "/api/v1/app/workbench/**"
                );
    }
}
