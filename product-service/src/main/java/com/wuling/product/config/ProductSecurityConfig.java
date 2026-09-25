package com.wuling.product.config;

import com.wuling.security.AdminAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 商品服务鉴权拦截器注册（第 13 期；管理端鉴权修复）。
 *
 * <p>需登录：管理端商品维护（{@code /api/v1/admin/product/**}）。
 * 该前缀同时被网关 {@code GatewayAuthPolicy} 判定为需管理端登录，
 * 两层校验保持一致（网关为第一道，本拦截器为第二道）。
 *
 * <p><b>修复记录（2026-09-23）</b>：此处原先注册的是
 * {@link com.wuling.security.MiniAppAuthInterceptor}（小程序拦截器，
 * 强校验 {@code type == "mini"}）。而管理端 token 的 type 为 {@code "access"}，
 * 导致后台访问商品接口**必然 401「无效的令牌」**（小程序访问则正常）。
 * 现改用 {@link AdminAuthInterceptor}（校验 {@code type == "access"}）。
 *
 * <p>公开：小程序菜单与商品详情（{@code /api/v1/app/menu}、
 * {@code /api/v1/app/products/**}）—— 属公开商品数据，与
 * {@code GatewayAuthPolicy} 的 PUBLIC 清单一致，不加入拦截清单。
 */
@Configuration
public class ProductSecurityConfig implements WebMvcConfigurer {

    private final AdminAuthInterceptor adminAuthInterceptor;

    public ProductSecurityConfig(AdminAuthInterceptor adminAuthInterceptor) {
        this.adminAuthInterceptor = adminAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns(
                        "/api/v1/admin/product/**"
                );
        // 小程序菜单 / 商品详情为公开数据，不加入拦截清单。
    }
}
