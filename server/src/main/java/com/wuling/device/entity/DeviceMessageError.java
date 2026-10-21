package com.wuling.device.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设备队列异常报文留档。
 *
 * <p>解析失败 / 未知 messagetype 的报文无法进入业务链路：
 * 既不能静默丢弃（对方调整报文格式时会丢数据），
 * 也不能一直不 ack（一条坏消息会被 MNS 无限重投）。
 * 因此落库留档后 ack，供事后核对与重放。
 */
@Data
@TableName("device_message_error")
public class DeviceMessageError {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 来源 MNS 消息 ID */
    private String sourceMessageId;

    private String messagetype;

    /** 原始 messageBody（超长会截断） */
    private String rawBody;

    private String errorReason;

    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}
