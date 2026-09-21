package com.wuling.common.cache;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 校验 SN 签名算法与迁移前的前端实现一致。
 *
 * 背景：签名逻辑原先在 src/utils/tencent-map.ts（JS），现已迁移到服务端。
 * 若两边算法不一致，腾讯接口会返回签名校验失败，且现象不直观，
 * 因此这里用固定输入锁定算法输出（参照值由原前端实现计算得出）。
 */
class GeoCodeSignTest {

    private static final String GEOCODER_PATH = "/ws/geocoder/v1/";

    /** 与前端一致的 32 位小写十六进制 MD5 */
    private static String md5(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    @Test
    void md5ShouldMatchLowerCaseHexOfKnownInput() throws Exception {
        assertEquals("808667a825b9c2d8d247ce851e9d9d24",
                md5(GEOCODER_PATH
                        + "?address=湖南省长沙市长沙县星沙街道开元东路288号"
                        + "&key=DVIBZ-A7X37-GXBX6-PHALK-GVS6V-5HBBQ"
                        + "tPEAfvpk5Og0JuylNxIuqcVIpPD4aRB6"),
                "签名必须与迁移前的前端实现保持一致");
    }

    @Test
    void md5ShouldBe32CharsLowerCase() throws Exception {
        String sig = md5("wuling");
        assertEquals(32, sig.length(), "MD5 必须是 32 位");
        assertEquals(sig.toLowerCase(), sig, "MD5 必须是小写");
    }

    @Test
    void signatureChangesWhenAddressChanges() throws Exception {
        String a = md5(GEOCODER_PATH + "?address=北京&key=K");
        String b = md5(GEOCODER_PATH + "?address=上海&key=K");
        assertNotEquals(a, b, "不同地址的签名必须不同");
    }

    /** GeoCodeService 内部签名方法应与本测试实现同源 */
    @Test
    void serviceSignatureShouldMatchReference() throws Exception {
        GeoCodeService service = new GeoCodeService(null);
        Method method = GeoCodeService.class.getDeclaredMethod("md5", String.class);
        method.setAccessible(true);
        String actual = (String) method.invoke(service,
                GEOCODER_PATH
                        + "?address=湖南省长沙市长沙县星沙街道开元东路288号"
                        + "&key=DVIBZ-A7X37-GXBX6-PHALK-GVS6V-5HBBQ"
                        + "tPEAfvpk5Og0JuylNxIuqcVIpPD4aRB6");
        assertEquals("808667a825b9c2d8d247ce851e9d9d24", actual,
                "GeoCodeService 的签名实现必须与参照一致");
    }
}