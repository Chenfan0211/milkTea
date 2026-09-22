package com.wuling.product.config;

import com.wuling.security.MiniAppAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 商品服务鉴权拦截器注册（第 13 期）。
 *
 * <p>需登录：管理端商品维护（{@code /api/v1/admin/product/**}）。
 * 该前缀同时被网关 {@code GatewayAuthPolicy} 判定为需管理端登录，
 * 两层校验保持一致（网关为第一道，本拦截器为第二道）。
 *
 * <p>公开：小程序菜单与商品详情（{@code /api/v1/app/menu}、
 * {@code /api/v1/app/products/**}）—— 属公开商品数据，
 * 与 {@code GatewayAuthPolicy} 的 PUBLIC 清单一致。
 *
 * <p>不受本拦截器管辖：{@code /internal/**}（服务间只读调用，
 * 仅回环可达，由 SecurityConfig 显式 permitAll）。
 */
@Configuration
public class ProductSecurityConfig implements WebMvcConfigurer {

    private final MiniAppAuthInterceptor miniAppAuthInterceptor;

    public ProductSecurityConfig(MiniAppAuthInterceptor miniAppAuthInterceptor) {
        this.miniAppAuthInterceptor = miniAppAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(miniAppAuthInterceptor)
                .addPathPatterns(
                        "/api/v1/admin/product/**"
                );
        // 小程序菜单 / 商品详情为公开数据，不加入拦截清单。
    }
}
