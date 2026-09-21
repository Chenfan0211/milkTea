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
 * 因此这里用固定输入锁定算法输出。
 *
 * 注意：测试使用虚构的 Key / SK，不涉及真实凭据。
 * 用例覆盖中文地址，可同时验证 UTF-8 编码下前后端结果一致。
 */
class GeoCodeSignTest {

    private static final String GEOCODER_PATH = "/ws/geocoder/v1/";

    /** 虚构测试凭据（非真实密钥） */
    private static final String TEST_ADDRESS = "测试地址示例";
    private static final String TEST_KEY = "TESTKEY-0000-0000-0000-0000-0000";
    private static final String TEST_SK = "TESTSECRETKEY000000000000000000";

    /** 参照值：由迁移前的前端实现（同算法）对上述虚构输入计算得出 */
    private static final String EXPECTED_SIG = "9151d7951a06662e038f86bae1ed76f3";

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

    /** 按腾讯 SN 规则拼签名串：path?k1=v1&k2=v2 + SK（value 不 URL 编码） */
    private static String buildSignSource(String address, String key, String sk) {
        return GEOCODER_PATH + "?address=" + address + "&key=" + key + sk;
    }

    @Test
    void md5ShouldMatchReferenceForKnownInput() throws Exception {
        assertEquals(EXPECTED_SIG,
                md5(buildSignSource(TEST_ADDRESS, TEST_KEY, TEST_SK)),
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
        String a = md5(buildSignSource("地址甲", TEST_KEY, TEST_SK));
        String b = md5(buildSignSource("地址乙", TEST_KEY, TEST_SK));
        assertNotEquals(a, b, "不同地址的签名必须不同");
    }

    @Test
    void signatureChangesWhenSecretChanges() throws Exception {
        String a = md5(buildSignSource(TEST_ADDRESS, TEST_KEY, TEST_SK));
        String b = md5(buildSignSource(TEST_ADDRESS, TEST_KEY, TEST_SK + "X"));
        assertNotEquals(a, b, "不同 SK 的签名必须不同");
    }

    /** GeoCodeService 内部签名方法应与本测试实现同源 */
    @Test
    void serviceSignatureShouldMatchReference() throws Exception {
        GeoCodeService service = new GeoCodeService(null);
        Method method = GeoCodeService.class.getDeclaredMethod("md5", String.class);
        method.setAccessible(true);
        String actual = (String) method.invoke(service, buildSignSource(TEST_ADDRESS, TEST_KEY, TEST_SK));
        assertEquals(EXPECTED_SIG, actual,
                "GeoCodeService 的签名实现必须与参照一致");
    }
}