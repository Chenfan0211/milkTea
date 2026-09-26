package com.wuling.auth.security;

import com.wuling.security.AdminUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final UserDetailsServiceImpl userDetailsService;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider, UserDetailsServiceImpl userDetailsService) {
        this.tokenProvider = tokenProvider;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 小程序端（/api/v1/app/**）使用独立的 mini token，由 MiniAppAuthInterceptor 处理，
        // 这里必须跳过，否则小程序 token 会被后台 access token 校验误判为无效。
        return request.getRequestURI().startsWith("/api/v1/app/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims claims = tokenProvider.parse(token);
                if (!JwtTokenProvider.TYPE_ACCESS.equals(claims.get("type", String.class))) {
                    writeError(response, 8888, "无效的令牌");
                    return;
                }
                String username = claims.get("username", String.class);
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);

                // 填充 AdminUser（ThreadLocal），供 RouteController / AdminRbacGuard 等
                // 通过 AdminUser.getUserId() 读取当前登录后台账号。
                //
                // 背景（2026-09-25 修复）：RouteController#getUserRoutes 依赖 AdminUser
                // 判断登录态，但此前本过滤器只写 SecurityContext、从不写 AdminUser，
                // 导致 AdminUser.getUserId() 恒为 null，/route/getUserRoutes 对所有账号
                // 都返回空菜单（dynamic 菜单模式失效）。这里复用 SecurityContext 里已解析的
                // AdminUserDetails 的 userId，避免二次解析 token。
                if (userDetails instanceof AdminUserDetails adminUserDetails) {
                    AdminUser.set(adminUserDetails.getUserId(), adminUserDetails.getUsername());
                }
            } catch (ExpiredJwtException e) {
                writeError(response, 9999, "登录已过期");
                return;
            } catch (Exception e) {
                writeError(response, 8888, "无效的令牌");
                return;
            }
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 与 AdminAuthInterceptor#afterCompletion 一致：请求结束即清理，
            // 防止 Tomcat 线程复用导致上一个请求的 userId 串到下一个请求。
            AdminUser.clear();
        }
    }

    private void writeError(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}");
    }
}

