package com.wuling.device.mns;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;

/**
 * MNS 设备报文解析器。
 *
 * <p>报文形态（对方文档 + 实测示例）：
 * <pre>
 * messageBody = Base64( {
 *     "messagetype": "status" | "upload",
 *     "payload": "{\"deviceSN\":\"ZPDS2512090086G\", ... }"   // 注意 payload 是【字符串】，需二次解析
 * } )
 * </pre>
 *
 * <p><b>为什么要兼容「已经是明文 JSON」的输入</b>：
 * 官方 SDK 在反序列化接收响应时已做了一次 Base64 解码，不同版本/取值方法
 * （{@code getMessageBody()} / {@code getMessageBodyAsString()} / {@code getMessageBodyAsRawString()}）
 * 拿到的可能是明文也可能是 Base64 原文。这里对候选值逐个尝试
 * 「明文 JSON → Base64 解码后 JSON」，避免把「SDK 已经解码」当成错误。
 *
 * <p><b>校验口径</b>：deviceSn 与 baseCreateTime 必须存在且可解析 ——
 * 它们是落库的唯一键组成部分，缺失会导致数据无法归属/去重，
 * 因此宁可记入 device_message_error 表也不能猜。
 */
@Component
public class MnsMessageParser {

    private static final Logger log = LoggerFactory.getLogger(MnsMessageParser.class);

    /** 统一输出格式（与库中 DATETIME 文本口径一致） */
    private static final DateTimeFormatter OUT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 容忍的输入时间格式（对方目前给的是 yyyy-MM-dd HH:mm:ss） */
    private static final List<DateTimeFormatter> IN_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 依次尝试候选报文（明文或 Base64），返回首个解析成功的信封。
     *
     * @param candidates SDK 不同取值方法拿到的报文文本
     * @param sourceMessageId MNS 消息 ID
     */
    public DeviceMessageEnvelope parse(List<String> candidates, String sourceMessageId) {
        MnsParseException last = null;
        for (String candidate : candidates) {
            if (!StringUtils.hasText(candidate)) {
                continue;
            }
            try {
                return parseOne(candidate, sourceMessageId);
            } catch (MnsParseException e) {
                last = e;
            }
        }
        if (last != null) {
            throw last;
        }
        throw new MnsParseException("", "报文为空，无法解析");
    }

    /** 单条报文解析（自动识别明文/Base64）。 */
    public DeviceMessageEnvelope parse(String body, String sourceMessageId) {
        return parseOne(body, sourceMessageId);
    }

    private DeviceMessageEnvelope parseOne(String body, String sourceMessageId) {
        String json = toJsonText(body);
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new MnsParseException("", "messageBody 不是合法 JSON：" + e.getMessage(), e);
        }

        String messagetype = text(root, "messagetype", "messageType", "msgType");
        if (!StringUtils.hasText(messagetype)) {
            throw new MnsParseException("", "报文缺少 messagetype 字段");
        }
        messagetype = messagetype.trim();
        if (!DeviceMessageEnvelope.TYPE_STATUS.equals(messagetype)
                && !DeviceMessageEnvelope.TYPE_UPLOAD.equals(messagetype)) {
            throw new MnsParseException(messagetype, "未知的 messagetype：" + messagetype);
        }

        // payload 是字符串形式的 JSON，需二次解析
        JsonNode payloadNode = root.get("payload");
        if (payloadNode == null || payloadNode.isNull()) {
            throw new MnsParseException(messagetype, "报文缺少 payload 字段");
        }
        JsonNode payload;
        if (payloadNode.isTextual()) {
            String payloadText = payloadNode.asText();
            if (!StringUtils.hasText(payloadText)) {
                throw new MnsParseException(messagetype, "payload 为空");
            }
            try {
                payload = objectMapper.readTree(payloadText);
            } catch (Exception e) {
                throw new MnsParseException(messagetype, "payload 不是合法 JSON：" + e.getMessage(), e);
            }
        } else {
            // 兼容 payload 直接是对象的情况（对方若调整报文结构）
            payload = payloadNode;
        }

        DeviceMessageEnvelope envelope = new DeviceMessageEnvelope();
        envelope.setType(messagetype);
        envelope.setSourceMessageId(sourceMessageId);
        envelope.setRawPayload(payload.toString());
        envelope.setDeviceSn(text(payload, "deviceSN", "deviceSn", "devicesSN"));
        envelope.setDeviceName(text(payload, "deviceName"));
        envelope.setReportTime(normalizeTime(text(payload, "baseCreateTime", "createTime"), messagetype));

        if (!StringUtils.hasText(envelope.getDeviceSn())) {
            throw new MnsParseException(messagetype, "payload 缺少 deviceSN，无法归属设备");
        }
        envelope.setDeviceSn(envelope.getDeviceSn().trim());

        if (DeviceMessageEnvelope.TYPE_STATUS.equals(messagetype)) {
            String status = text(payload, "status", "onlineStatus");
            if (!StringUtils.hasText(status)) {
                throw new MnsParseException(messagetype, "status 报文缺少 status 字段");
            }
            envelope.setOnlineStatus(status.trim().toUpperCase());
        } else {
            envelope.setTeaCode(text(payload, "teaCode"));
            envelope.setTeaName(text(payload, "teaName"));
            envelope.setCustomerCode(text(payload, "customer", "customerCode"));
            envelope.setMadeDay(text(payload, "madeDay"));
            // 附件 xlsx 写的是 seriaNumber，报文示例是 serialNumber，两种都认
            envelope.setSerialNumber(text(payload, "serialNumber", "seriaNumber"));
        }
        return envelope;
    }

    /**
     * 把候选报文归一成 JSON 文本：已经是 JSON 直接用，否则按 Base64 解码。
     */
    private String toJsonText(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("{")) {
            return trimmed;
        }
        try {
            byte[] decoded = Base64.getMimeDecoder().decode(trimmed);
            String text = new String(decoded, StandardCharsets.UTF_8).trim();
            if (text.startsWith("{")) {
                return text;
            }
        } catch (IllegalArgumentException e) {
            log.debug("报文不是 Base64：{}", e.getMessage());
        }
        throw new MnsParseException("", "messageBody 既不是 JSON 也不是 Base64 编码的 JSON");
    }

    /** 取字段文本（兼容数字/布尔，按第一个命中的键名返回）。 */
    private String text(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull()) {
                String s = value.asText();
                if (StringUtils.hasText(s)) {
                    return s;
                }
            }
        }
        return null;
    }

    /**
     * 归一化上报时间。
     *
     * <p>时间无法解析时必须失败（而非用 now() 兜底）：
     * 上传记录的 base_create_time 是唯一键的一部分，
     * 用当前时间兜底会让「同一条消息重投」变成两条记录。
     */
    private String normalizeTime(String value, String messagetype) {
        if (!StringUtils.hasText(value)) {
            throw new MnsParseException(messagetype, "payload 缺少 baseCreateTime，无法确定上报时间");
        }
        String trimmed = value.trim().replace('T', ' ');
        for (DateTimeFormatter format : IN_FORMATS) {
            try {
                LocalDateTime parsed = LocalDateTime.parse(trimmed, format);
                return parsed.format(OUT_FORMAT);
            } catch (DateTimeParseException ignore) {
                // 继续尝试下一种格式
            }
        }
        throw new MnsParseException(messagetype, "baseCreateTime 格式无法识别：" + value);
    }
}
