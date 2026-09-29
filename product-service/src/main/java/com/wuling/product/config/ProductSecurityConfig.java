package com.wuling.product.config;

import com.wuling.security.AdminAuthInterceptor;
import com.wuling.security.MiniAppAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 商品服务鉴权拦截器注册（第 13 期；门店选品修复）。
 *
 * <p>需登录的接口分两类，token 类型不同，必须用不同拦截器：
 * <ul>
 *   <li>管理端商品维护（{@code /api/v1/admin/product/**}）—— 运营后台 token（type=access）
 *       用 {@link AdminAuthInterceptor}；</li>
 *   <li>小程序门店选品（{@code /api/v1/app/workbench/store/*&#47;products} 系列）——
 *       小程序 token（type=mini）用 {@link MiniAppAuthInterceptor}。</li>
 * </ul>
 *
 * <p><b>修复记录（2026-09-29）</b>：选品接口上线时只挂了管理端拦截器，
 * 小程序调用因 type 不匹配被拒（401）；且 {@code CurrentUser} 从未写入，
 * 即使放行也会在 {@code requireStoreOperator} 处拿不到用户。现按 token 类型分挂。
 *
 * <p>公开：小程序菜单与商品详情（{@code /api/v1/app/menu}、
 * {@code /api/v1/app/products/**}）—— 属公开商品数据，与
 * {@code GatewayAuthPolicy} 的 PUBLIC 清单一致，不加入拦截清单。
 */
@Configuration
public class ProductSecurityConfig implements WebMvcConfigurer {

    private final AdminAuthInterceptor adminAuthInterceptor;
    private final MiniAppAuthInterceptor miniAppAuthInterceptor;

    public ProductSecurityConfig(AdminAuthInterceptor adminAuthInterceptor,
                                 MiniAppAuthInterceptor miniAppAuthInterceptor) {
        this.adminAuthInterceptor = adminAuthInterceptor;
        this.miniAppAuthInterceptor = miniAppAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns(
                        "/api/v1/admin/product/**"
                );
        // 门店选品：读写都必须登录，且服务端按 JWT 校验门店归属（越权防护）
        registry.addInterceptor(miniAppAuthInterceptor)
                .addPathPatterns(
                        "/api/v1/app/workbench/store/*/products",
                        "/api/v1/app/workbench/store/*/products/**"
                );
        // 小程序菜单 / 商品详情为公开数据，不加入拦截清单。
    }
}
