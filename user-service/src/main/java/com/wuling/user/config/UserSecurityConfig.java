package com.wuling.user.config;

import com.wuling.security.MiniAppAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 用户服务鉴权拦截器注册（第 11 期）。
 *
 * 需登录的接口（与 server 原 WebConfig 中 auth 相关条目保持一致）：
 * me / sms / avatar / nickname / phone / profile / location。
 *
 * 公开：wx-login（获取凭证的入口）。
 */
@Configuration
public class UserSecurityConfig implements WebMvcConfigurer {

    private final MiniAppAuthInterceptor miniAppAuthInterceptor;

    public UserSecurityConfig(MiniAppAuthInterceptor miniAppAuthInterceptor) {
        this.miniAppAuthInterceptor = miniAppAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(miniAppAuthInterceptor)
                .addPathPatterns(
                        "/api/v1/app/auth/me",
                        "/api/v1/app/auth/sms/bind",
                        "/api/v1/app/auth/avatar",
                        "/api/v1/app/auth/nickname",
                        "/api/v1/app/auth/phone",
                        "/api/v1/app/auth/profile",
                        "/api/v1/app/auth/location"
                );
        // wx-login 不在此列：它是获取凭证的入口，本身无需登录。
    }
}
