package com.wuling.common.logging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敏感数据脱敏与日志截断测试。
 *
 * <p><b>为什么测 data URL 截断</b>：2026-11-23 事故中，日志里出现
 * {@code "image":"data:image/png;base64,...(共2217873字符)"} ——
 * 单次请求写 2MB，日志迅速膨胀且无法阅读。
 * 截断后应只保留「类型 + 长度」这两个有诊断价值的信息。
 */
class SensitiveDataMaskerTest {

    @Test
    void dataUrlBodyShouldBeTruncatedKeepingLength() {
        String base64Body = "iVBORw0KGgo".repeat(100); // 1000 字符
        String json = "{\"image\":\"data:image/png;base64," + base64Body + "\"}";

        String masked = SensitiveDataMasker.maskJson(json);

        assertTrue(masked.contains("data:image/png;base64,"), "应保留类型前缀");
        assertTrue(masked.contains("TRUNCATED"), "应标注已截断");
        assertTrue(masked.contains(String.valueOf(base64Body.length())), "应保留原始长度");
        assertFalse(masked.contains(base64Body.substring(0, 200)), "base64 主体不应出现");
        // 截断后总长应显著小于原始
        assertTrue(masked.length() < 200, "截断后应远小于原长：" + masked.length());
    }

    @Test
    void shortDataUrlShouldBeLeftAlone() {
        // 短 base64（如 1x1 像素图标）不截断：可能是合法的极小图
        String json = "{\"icon\":\"data:image/png;base64,AAAA\"}";

        String masked = SensitiveDataMasker.maskJson(json);

        assertTrue(masked.contains("data:image/png;base64,AAAA"), "短 data URL 保持原样");
        assertFalse(masked.contains("TRUNCATED"));
    }

    @Test
    void normalFieldsShouldBeUnaffected() {
        String json = "{\"name\":\"经典茉莉\",\"price\":1500}";

        String masked = SensitiveDataMasker.maskJson(json);

        assertEquals(json, masked, "普通字段不应被改动");
    }

    @Test
    void phoneShouldStillBeMaskedWhenCombinedWithDataUrl() {
        String base64Body = "A".repeat(500);
        String json = "{\"image\":\"data:image/png;base64," + base64Body
                + "\",\"phone\":\"13812348000\"}";

        String masked = SensitiveDataMasker.maskJson(json);

        assertTrue(masked.contains("TRUNCATED"), "data URL 应截断");
        assertTrue(masked.contains("138****8000"), "手机号脱敏规则仍生效");
    }

    @Test
    void nullAndEmptyShouldBeSafe() {
        assertEquals(null, SensitiveDataMasker.maskJson(null));
        assertEquals("", SensitiveDataMasker.maskJson(""));
    }
}
