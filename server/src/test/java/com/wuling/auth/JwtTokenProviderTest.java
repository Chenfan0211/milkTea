package com.wuling.auth;

import com.wuling.auth.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtTokenProviderTest {

    private final JwtTokenProvider provider = new JwtTokenProvider(
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes()),
            3600_000L,
            604800_000L);

    @Test
    void accessTokenShouldRoundTrip() {
        String token = provider.createAccessToken(1L, "admin");
        Claims claims = provider.parse(token);
        assertEquals("1", claims.getSubject());
        assertEquals("admin", claims.get("username", String.class));
        assertEquals(JwtTokenProvider.TYPE_ACCESS, claims.get("type", String.class));
    }

    @Test
    void refreshTokenShouldCarryType() {
        String token = provider.createRefreshToken(1L, "admin");
        Claims claims = provider.parse(token);
        assertEquals(JwtTokenProvider.TYPE_REFRESH, claims.get("type", String.class));
    }

    @Test
    void invalidTokenShouldThrow() {
        assertThrows(Exception.class, () -> provider.parse("not-a-jwt"));
    }
}
