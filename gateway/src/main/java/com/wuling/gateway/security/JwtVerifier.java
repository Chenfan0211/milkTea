package com.wuling.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 网关侧 JWT 校验器。
 *
 * <p>与 auth-service / server 使用<b>同一个密钥</b>（{@code JWT_SECRET}），
 * 因此密钥缺失或过短时直接拒绝启动（fail-fast），避免网关放行未经验证的请求。
 *
 * <p>网关只做「签名与有效期」校验，不查库、不校验业务归属 ——
 * 归属校验由下游服务负责（纵深防御）。
 */
@Component
public class JwtVerifier {

    private static final int MIN_KEY_BYTES = 32;

    private final SecretKey key;

    public JwtVerifier(@Value("${app.jwt.secret:}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "未配置 JWT 密钥 app.jwt.secret（环境变量 JWT_SECRET）。"
                    + "网关依赖该密钥校验令牌，缺失会导致鉴权失效，服务拒绝启动。"
                    + "生成方式：openssl rand -base64 32");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(secret.trim());
        } catch (Exception e) {
            throw new IllegalStateException("JWT 密钥必须是合法的 Base64 字符串", e);
        }
        if (decoded.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "JWT 密钥长度不足：解码后 " + decoded.length + " 字节，HS256 要求至少 "
                    + MIN_KEY_BYTES + " 字节");
        }
        this.key = Keys.hmacShaKeyFor(decoded);
    }

    /**
     * 解析并校验令牌。
     *
     * @throws ExpiredException 令牌过期
     * @throws Exception        签名无效等其他异常
     */
    public Claims parse(String token) {
        try {
            return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        } catch (ExpiredJwtException e) {
            throw new ExpiredException();
        }
    }

    /** 令牌过期 */
    public static class ExpiredException extends RuntimeException {
        public ExpiredException() {
            super("token expired");
        }
    }
}
