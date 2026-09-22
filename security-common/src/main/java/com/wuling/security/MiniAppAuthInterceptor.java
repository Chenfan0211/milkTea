package com.wuling.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 小程序端鉴权拦截器。
 *
 * 作用：
 * - 从 Authorization: Bearer <token> 解析小程序 JWT，写入 CurrentUser；
 * - 未携带或无效 token 时返回 8888（与前端登出码一致）。
 *
 * 仅对「需要登录」的路径生效，由 WebConfig 注册时指定。
 */
@Component
public class MiniAppAuthInterceptor implements HandlerInterceptor {

    private final MiniAppTokenProvider tokenProvider;

    public MiniAppAuthInterceptor(MiniAppTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String header = request.getHeader("Authorization");
        if (!StringUtils.hasText(header) || !header.startsWith("Bearer ")) {
            return reject(response, 8888, "未登录或登录已过期");
        }
        try {
            Claims claims = tokenProvider.parse(header.substring(7));
            if (!MiniAppTokenProvider.TYPE_MINI.equals(claims.get("type", String.class))) {
                return reject(response, 8888, "无效的令牌");
            }
            CurrentUser.set(Long.valueOf(claims.getSubject()));
            return true;
        } catch (ExpiredJwtException e) {
            return reject(response, 9999, "登录已过期，请重新登录");
        } catch (Exception e) {
            return reject(response, 8888, "无效的令牌");
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        CurrentUser.clear();
    }

    private boolean reject(HttpServletResponse response, int code, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}");
        return false;
    }
}

