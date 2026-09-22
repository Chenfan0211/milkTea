package com.wuling.auth;

import com.wuling.auth.security.JwtTokenProvider;
import com.wuling.security.MiniAppTokenProvider;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 安全修复：JWT 密钥启动期校验。
 *
 * 原实现允许空的 / 过短的密钥，且仓库里带着公开默认值，
 * 任何人都能离线伪造 token。这里验证 fail-fast 行为。
 */
class JwtSecretValidationTest {

    private static final String VALID = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    @Test
    void validSecretShouldStart() {
        assertDoesNotThrow(() -> new JwtTokenProvider(VALID, 3600_000L, 604800_000L));
        assertDoesNotThrow(() -> new MiniAppTokenProvider(VALID, 3600_000L));
    }

    @Test
    void blankSecretShouldFailFast() {
        // 空值 = 未注入环境变量，必须拒绝启动
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider("", 1L, 1L));
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider("   ", 1L, 1L));
        assertThrows(IllegalStateException.class, () -> new MiniAppTokenProvider("", 1L));
    }

    @Test
    void nonBase64SecretShouldFailFast() {
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider("not-base64!!!", 1L, 1L));
    }

    @Test
    void tooShortSecretShouldFailFast() {
        // 16 字节 < HS256 要求的 32 字节
        String short16 = Base64.getEncoder().encodeToString("0123456789abcdef".getBytes());
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider(short16, 1L, 1L));
        assertThrows(IllegalStateException.class, () -> new MiniAppTokenProvider(short16, 1L));
    }

    @Test
    void legacyRepoDefaultShouldNotBeAcceptedAsStrongKey() {
        // 仓库旧默认值本身是合格的 32 字节密钥，故校验器不会拒绝它；
        // 真正的防线是 application.yml 不再提供该默认值（见配置文件）。
        // 此用例仅固化该事实，避免误以为校验器能识别"曾经的默认值"。
        String legacy = "N1uU6M8tUVZhUGLntH8DFKq4a3WrzrkyIADnGlEmr5c=";
        assertDoesNotThrow(() -> new JwtTokenProvider(legacy, 1L, 1L));
    }

    @Test
    void errorMessageShouldTellHowToGenerate() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new JwtTokenProvider("", 1L, 1L));
        assertTrue(e.getMessage().contains("JWT_SECRET"), "错误信息应指明所需的环境变量名");
        assertTrue(e.getMessage().contains("openssl rand"), "错误信息应给出生成建议");
    }
}
