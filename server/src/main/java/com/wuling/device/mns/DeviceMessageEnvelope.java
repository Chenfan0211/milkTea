package com.wuling.device.mns;

import lombok.Data;

/**
 * 设备消息规范化信封。
 *
 * <p>第三方（杭州智萃谷）原始报文经 {@link MnsMessageParser} 解析后统一成该结构，
 * 再转投内部 RabbitMQ 队列 {@code wuling.device.message.queue}，由业务消费者落库。
 *
 * <p><b>时间一律用字符串</b>（{@code yyyy-MM-dd HH:mm:ss}）：
 * MQ 消费基类用的是未注册 JavaTimeModule 的 ObjectMapper，
 * 直接放 LocalDateTime 会在序列化/反序列化时报错。
 */
@Data
public class DeviceMessageEnvelope {

    /** messagetype=status：设备上下线 */
    public static final String TYPE_STATUS = "status";

    /** messagetype=upload：胶囊冲泡记录 */
    public static final String TYPE_UPLOAD = "upload";

    /** 消息类型（status / upload） */
    private String type;

    /** 设备编码（报文 deviceSN），设备唯一标识 */
    private String deviceSn;

    /** 设备ID（报文 deviceName），如 869978072404913 */
    private String deviceName;

    /** status 消息：ONLINE / OFFLINE */
    private String onlineStatus;

    /** upload 消息：茶品代码（报文 teaCode） */
    private String teaCode;

    /** upload 消息：茶品名称 */
    private String teaName;

    /** upload 消息：客户代码（报文 customer） */
    private String customerCode;

    /** upload 消息：生产日期（报文 madeDay，yyyyMMdd） */
    private String madeDay;

    /** upload 消息：茶品序列号（报文 serialNumber，兼容 seriaNumber 拼写） */
    private String serialNumber;

    /** 上报时间（报文 baseCreateTime），格式 yyyy-MM-dd HH:mm:ss */
    private String reportTime;

    /** 原始 payload（JSON 文本，便于追溯） */
    private String rawPayload;

    /** 来源 MNS 消息 ID，用作内部 MQ 的 messageId（幂等键） */
    private String sourceMessageId;
}
