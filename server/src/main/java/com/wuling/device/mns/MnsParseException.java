package com.wuling.device.mns;

/**
 * 设备队列报文解析失败。
 *
 * <p>抛出该异常时，消费端会把原始报文写入 device_message_error 表后 ack，
 * 既不静默丢数据，也不让一条坏消息在 MNS 里无限重投。
 */
public class MnsParseException extends RuntimeException {

    private final String messagetype;

    public MnsParseException(String messagetype, String message) {
        super(message);
        this.messagetype = messagetype;
    }

    public MnsParseException(String messagetype, String message, Throwable cause) {
        super(message, cause);
        this.messagetype = messagetype;
    }

    /** 报文里的 messagetype（无法解析时为空字符串） */
    public String getMessagetype() {
        return messagetype == null ? "" : messagetype;
    }
}
