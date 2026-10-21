package com.wuling.user.tongue;

import com.wuling.user.config.TongueProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TongueSignerTest {

    @Test
    void signsPostWithMd5OfExactBodyBytes() {
        TongueProperties properties = properties("app-key", "test-secret");
        TongueSigner signer = new TongueSigner(properties);
        byte[] body = "{\"userSex\":\"1\",\"userAge\":30,\"tongueOnImg\":\"data:image/png;base64,AAAA\"}"
                .getBytes(StandardCharsets.UTF_8);

        TongueSigner.SignedHeaders headers = signer.signPost(body);

        assertEquals("app-key", headers.appKey());
        assertEquals("test-secret", headers.appSecret());
        assertTrue(headers.timestamp().matches("\\d+"));
        assertFalse(headers.nonce().contains("-"));
        assertEquals(md5Hex(body), headers.contentMd5());
        assertEquals(sha256Hex("appKey=app-key&timestamp=" + headers.timestamp()
                + "&nonce=" + headers.nonce()
                + "&contentMd5=" + headers.contentMd5()
                + "&appSecret=test-secret"), headers.sign());
    }

    @Test
    void signsGetWithUrlEncodedReportId() {
        TongueProperties properties = properties("app-key", "test-secret");
        TongueSigner signer = new TongueSigner(properties);

        TongueSigner.SignedHeaders headers = signer.signGet("R 1/中文");

        assertEquals(md5Hex("reportId=R%201%2F%E4%B8%AD%E6%96%87".getBytes(StandardCharsets.UTF_8)),
                headers.contentMd5());
        assertEquals(sha256Hex("appKey=app-key&timestamp=" + headers.timestamp()
                + "&nonce=" + headers.nonce()
                + "&contentMd5=" + headers.contentMd5()
                + "&appSecret=test-secret"), headers.sign());
    }

    private static TongueProperties properties(String appKey, String appSecret) {
        TongueProperties properties = new TongueProperties();
        properties.setAppKey(appKey);
        properties.setAppSecret(appSecret);
        return properties;
    }

    private static String md5Hex(byte[] bytes) {
        return digest("MD5", bytes);
    }

    private static String sha256Hex(String value) {
        return digest("SHA-256", value.getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(String algorithm, byte[] bytes) {
        try {
            return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
