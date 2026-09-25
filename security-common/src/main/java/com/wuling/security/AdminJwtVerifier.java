package com.wuling.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;

/**
 * 管理端（运营后台）JWT 校验器。
 *
 * <p>背景：管理端 token 与小程序 token 是两套体系：
 * <ul>
 *   <li>管理端：{@code type = "access"}，主体为 {@code sys_user.id}，由 auth-service 签发；</li>
 *   <li>小程序：{@code type = "mini"}，主体为 {@code app_user.id}，由 MiniAppTokenProvider 签发。</li>
 * </ul>
 *
 * <p><b>为什么需要本类</b>：{@link MiniAppAuthInterceptor} 强校验 {@code type == "mini"}，
 * 只能用于 {@code /api/v1/app/**}。若用它保护管理端路径 {@code /api/v1/admin/**}，
 * 后台 token（access）会被判为「无效的令牌」而必然 401。
 * 因此管理端路径必须使用本校验器。
 *
 * <p>与 auth-service 的 {@code JwtAuthenticationFilter} 保持一致：
 * 校验 type、区分过期（9999）与无效（8888）错误码。
 */
@Component
public class AdminJwtVerifier {

    /** 管理端访问令牌类型 */
    public static final String TYPE_ACCESS = "access";

    private final SecretKey key;

    public AdminJwtVerifier(@Value("${app.jwt.secret}") String secret) {
        // 与 MiniAppTokenProvider 一致：启动期强校验密钥，不合格直接拒绝启动
        this.key = Keys.hmacShaKeyFor(JwtSecretValidator.resolve(secret));
    }

    /**
     * 校验管理端 token。
     *
     * @param token 不含 "Bearer " 前缀的原始 token
     * @return 解析后的 Claims
     * @throws AdminTokenException 过期或无效
     */
    public Claims verify(String token) throws AdminTokenException {
        Claims claims;
        try {
            claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            throw new AdminTokenException(9999, "登录已过期，请重新登录");
        } catch (Exception e) {
            throw new AdminTokenException(8888, "无效的令牌");
        }
        if (!TYPE_ACCESS.equals(claims.get("type", String.class))) {
            throw new AdminTokenException(8888, "无效的令牌");
        }
        return claims;
    }

    /**
     * 从请求中校验管理端 token；失败时直接写出 401 响应。
     *
     * @return true = 校验通过；false = 已写出错误响应，调用方应中断
     */
    public boolean verifyRequest(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String header = request.getHeader("Authorization");
        if (!StringUtils.hasText(header) || !header.startsWith("Bearer ")) {
            reject(response, 8888, "未登录或登录已过期");
            return false;
        }
        try {
            Claims claims = verify(header.substring(7));
            AdminUser.set(Long.valueOf(claims.getSubject()),
                    claims.get("username", String.class));
            return true;
        } catch (AdminTokenException e) {
            reject(response, e.code(), e.getMessage());
            return false;
        }
    }

    private void reject(HttpServletResponse response, int code, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}");
    }

    /** 管理端 token 校验失败 */
    public static class AdminTokenException extends RuntimeException {
        private final int code;

        public AdminTokenException(int code, String message) {
            super(message);
            this.code = code;
        }

        public int code() {
            return code;
        }
    }
}
