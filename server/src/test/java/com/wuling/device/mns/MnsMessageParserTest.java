package com.wuling.device.mns;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设备报文解析测试。
 *
 * <p>用例直接取自对方文档中的示例报文（含 payload 内的 \r\n 转义、
 * 数字型 teaCode/customer/serialNumber），确保「拿不到密钥也能验证解析是否正确」。
 */
class MnsMessageParserTest {

    private final MnsMessageParser parser = new MnsMessageParser();

    /** 设备状态报文（Base64 编码，报文结构与对方文档一致）。 */
    private static final String STATUS_JSON = """
            {"messagetype": "status",
             "payload": "{\\r\\n \\"deviceSN\\": \\"ZPDS2512090086G\\",\\r\\n \\"baseCreateTime\\":\\"2026-03-10 19:32:25\\",\\r\\n  \\"status\\": \\"ONLINE\\",\\r\\n            \\"deviceName\\": \\"869978072404913\\"\\r\\n}"}
            """;

    /** 冲泡记录报文（Base64 编码）。 */
    private static final String UPLOAD_JSON = """
            {"messagetype": "upload",
             "payload": "{\\r\\n \\"teaCode\\": 209,\\r\\n \\"teaName\\": \\"欧米伽-3植物乳\\",\\r\\n \\"customer\\": 90000001,\\r\\n \\"madeDay\\": \\"20251021\\",\\r\\n \\"serialNumber\\": 408,\\r\\n \\"baseCreateTime\\": \\"2026-03-10 21:16:04\\",\\r\\n \\"deviceName\\": \\"869978072404913\\",\\r\\n \\"deviceSN\\": \\"ZPDS2512090086G\\"\\r\\n}"}
            """;

    private static String base64(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesStatusMessageFromBase64Body() {
        DeviceMessageEnvelope envelope = parser.parse(base64(STATUS_JSON), "msg-1");

        assertEquals(DeviceMessageEnvelope.TYPE_STATUS, envelope.getType());
        assertEquals("ZPDS2512090086G", envelope.getDeviceSn());
        assertEquals("869978072404913", envelope.getDeviceName());
        assertEquals("ONLINE", envelope.getOnlineStatus());
        assertEquals("2026-03-10 19:32:25", envelope.getReportTime());
        assertEquals("msg-1", envelope.getSourceMessageId());
    }

    @Test
    void parsesUploadMessageWithNumericFields() {
        DeviceMessageEnvelope envelope = parser.parse(base64(UPLOAD_JSON), "msg-2");

        assertEquals(DeviceMessageEnvelope.TYPE_UPLOAD, envelope.getType());
        assertEquals("ZPDS2512090086G", envelope.getDeviceSn());
        assertEquals("209", envelope.getTeaCode());
        assertEquals("欧米伽-3植物乳", envelope.getTeaName());
        assertEquals("90000001", envelope.getCustomerCode());
        assertEquals("20251021", envelope.getMadeDay());
        assertEquals("408", envelope.getSerialNumber());
        assertEquals("2026-03-10 21:16:04", envelope.getReportTime());
        assertTrue(envelope.getRawPayload().contains("ZPDS2512090086G"));
    }

    @Test
    void acceptsPlainJsonBodyBecauseSdkMayAlreadyDecodeBase64() {
        DeviceMessageEnvelope envelope = parser.parse(STATUS_JSON.trim(), "msg-3");

        assertEquals("ZPDS2512090086G", envelope.getDeviceSn());
        assertEquals("OFFLINE", parser.parse(STATUS_JSON.replace("ONLINE", "OFFLINE").trim(), "m").getOnlineStatus());
    }

    @Test
    void acceptsSeriaNumberSpellingFromInterfaceSheet() {
        String json = """
                {"messagetype":"upload","payload":"{\\"serialNumber\\":\\"\\",\\"seriaNumber\\":408,\\"deviceSN\\":\\"SN-2\\",\\"baseCreateTime\\":\\"2026-03-10 21:16:04\\"}"}
                """;

        DeviceMessageEnvelope envelope = parser.parse(json.trim(), "msg-4");

        assertEquals("408", envelope.getSerialNumber());
    }

    @Test
    void normalizesTimeWithSlashAndCompactFormats() {
        String slash = """
                {"messagetype":"status","payload":"{\\"deviceSN\\":\\"SN-3\\",\\"status\\":\\"online\\",\\"baseCreateTime\\":\\"2026/03/10 19:32:25\\"}"}
                """;
        String compact = """
                {"messagetype":"status","payload":"{\\"deviceSN\\":\\"SN-3\\",\\"status\\":\\"online\\",\\"baseCreateTime\\":\\"20260310193225\\"}"}
                """;

        assertEquals("2026-03-10 19:32:25", parser.parse(slash.trim(), "m").getReportTime());
        assertEquals("2026-03-10 19:32:25", parser.parse(compact.trim(), "m").getReportTime());
        // 状态统一大写，避免 ONLINE/online 混用导致状态判断失效
        assertEquals("ONLINE", parser.parse(slash.trim(), "m").getOnlineStatus());
    }

    @Test
    void keepsFirstUsableCandidateWhenSdkReturnsRawAndDecodedBody() {
        DeviceMessageEnvelope envelope = parser.parse(
                List.of("", base64(STATUS_JSON), STATUS_JSON.trim()), "msg-5");

        assertEquals("ZPDS2512090086G", envelope.getDeviceSn());
    }

    @Test
    void rejectsUnknownMessagetypeWithTypePreserved() {
        MnsParseException error = assertThrows(MnsParseException.class,
                () -> parser.parse("{\"messagetype\":\"heartbeat\",\"payload\":\"{}\"}", "m"));

        assertEquals("heartbeat", error.getMessagetype());
    }

    @Test
    void rejectsMalformedBody() {
        assertThrows(MnsParseException.class, () -> parser.parse("not-a-base64-@@@", "m"));
        assertThrows(MnsParseException.class, () -> parser.parse(base64("{\"foo\":1}"), "m"));
        assertThrows(MnsParseException.class,
                () -> parser.parse(base64("{\"messagetype\":\"status\",\"payload\":\"not-json\"}"), "m"));
    }

    @Test
    void rejectsMissingDeviceSnOrTime() {
        // 缺 deviceSN：无法归属设备，必须失败而不是猜
        assertThrows(MnsParseException.class, () -> parser.parse(base64(
                "{\"messagetype\":\"status\",\"payload\":\"{\\\"status\\\":\\\"ONLINE\\\",\\\"baseCreateTime\\\":\\\"2026-03-10 19:32:25\\\"}\"}"), "m"));
        // 缺 baseCreateTime：上传记录的唯一键依赖它，用 now() 兜底会导致重复入库
        assertThrows(MnsParseException.class, () -> parser.parse(base64(
                "{\"messagetype\":\"upload\",\"payload\":\"{\\\"deviceSN\\\":\\\"SN-9\\\"}\"}"), "m"));
        // 缺 status：状态报文无状态值没有意义
        assertThrows(MnsParseException.class, () -> parser.parse(base64(
                "{\"messagetype\":\"status\",\"payload\":\"{\\\"deviceSN\\\":\\\"SN-9\\\",\\\"baseCreateTime\\\":\\\"2026-03-10 19:32:25\\\"}\"}"), "m"));
    }
}
