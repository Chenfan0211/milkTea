package com.wuling.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理端（运营后台）鉴权拦截器。
 *
 * <p>用于保护 {@code /api/v1/admin/**} 等管理端路径：
 * 校验 {@code type = "access"} 的管理端 token，并把当前用户写入 {@link AdminUser}。
 *
 * <p><b>不要用它保护 {@code /api/v1/app/**}</b> —— 小程序路径应使用
 * {@link MiniAppAuthInterceptor}（校验 {@code type = "mini"}）。
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final AdminJwtVerifier verifier;

    public AdminAuthInterceptor(AdminJwtVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        return verifier.verifyRequest(request, response);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AdminUser.clear();
    }
}
