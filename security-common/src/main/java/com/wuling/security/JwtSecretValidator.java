package com.wuling.security;

import io.jsonwebtoken.io.Decoders;

import java.nio.charset.StandardCharsets;

/**
 * JWT 密钥校验工具（P1 安全修复）。
 *
 * <p>背景：原默认密钥硬编码在 {@code application.yml}，等同于公开密钥，
 * 任何人可离线伪造管理端 / 小程序 token。现已移除默认值，
 * 并在此处做启动期强校验（fail-fast），避免服务带着弱密钥或空密钥上线。
 *
 * <p>校验规则：
 * <ul>
 *   <li>必须非空；</li>
 *   <li>必须是合法 Base64；</li>
 *   <li>解码后至少 32 字节（HS256 要求 256 位），否则拒绝启动。</li>
 * </ul>
 */
public final class JwtSecretValidator {

    /** HS256 要求的最小密钥长度（字节） */
    public static final int MIN_KEY_BYTES = 32;

    private JwtSecretValidator() {
    }

    /**
     * 校验并返回 JWT 密钥字节。
     *
     * @param secret Base64 编码的密钥
     * @throws IllegalStateException 密钥缺失、非 Base64 或长度不足
     */
    public static byte[] resolve(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "未配置 JWT 密钥 app.jwt.secret（环境变量 JWT_SECRET）。"
                    + "JWT 密钥用于签发与校验登录凭证，缺失会导致任何人都能伪造 token，服务拒绝启动。"
                    + "生成方式：openssl rand -base64 32");
        }
        byte[] decoded;
        try {
            decoded = Decoders.BASE64.decode(secret.trim());
        } catch (Exception e) {
            throw new IllegalStateException("JWT 密钥必须是合法的 Base64 字符串，当前值无法解码", e);
        }
        if (decoded.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "JWT 密钥长度不足：解码后 " + decoded.length + " 字节，HS256 要求至少 "
                    + MIN_KEY_BYTES + " 字节。生成方式：openssl rand -base64 32");
        }
        return decoded;
    }

    /** 供测试判断密钥是否为已知的仓库旧默认值 */
    public static boolean isLegacyDefault(String secret) {
        return secret != null
                && secret.trim().equals("N1uU6M8tUVZhUGLntH8DFKq4a3WrzrkyIADnGlEmr5c=");
    }

    public static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
