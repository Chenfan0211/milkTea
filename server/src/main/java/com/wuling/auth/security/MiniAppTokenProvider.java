package com.wuling.auth.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

/**
 * 小程序端 JWT 签发与解析。
 *
 * 与后台 token 的区别：
 * - type 为 "mini"，与后台的 access/refresh 区分，避免混用；
 * - 主体为 app_user.id，业务接口据此校验数据归属。
 */
@Component
public class MiniAppTokenProvider {

    public static final String TYPE_MINI = "mini";

    private final SecretKey key;
    private final long ttl;

    public MiniAppTokenProvider(@Value("${app.jwt.secret}") String secret,
                                @Value("${app.jwt.access-token-ttl}") long ttl) {
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.ttl = ttl;
    }

    public String createToken(Long userId, String openId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("openId", openId)
                .claim("type", TYPE_MINI)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttl))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
