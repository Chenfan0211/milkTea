package com.wuling.auth.security;

import com.wuling.security.JwtSecretValidator;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class JwtTokenProvider {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final long accessTtl;
    private final long refreshTtl;

    public JwtTokenProvider(@Value("${app.jwt.secret}") String secret,
                            @Value("${app.jwt.access-token-ttl}") long accessTtl,
                            @Value("${app.jwt.refresh-token-ttl}") long refreshTtl) {
        // P1 安全修复：启动期强校验密钥（非空、合法 Base64、>=32 字节），不合格直接拒绝启动
        this.key = Keys.hmacShaKeyFor(JwtSecretValidator.resolve(secret));
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
    }

    public String createAccessToken(Long userId, String username) {
        return createToken(userId, username, TYPE_ACCESS, accessTtl);
    }

    public String createRefreshToken(Long userId, String username) {
        return createToken(userId, username, TYPE_REFRESH, refreshTtl);
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    private String createToken(Long userId, String username, String type, long ttl) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + ttl);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("type", type)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }
}
