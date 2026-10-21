package com.wuling.device.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设备在线/离线状态变更流水。
 *
 * <p>只在「状态真正变化」时写入，避免设备每分钟上报把表撑爆；
 * 设备表始终保留最新状态与最近上报时间。
 */
@Data
@TableName("device_status_log")
public class DeviceStatusLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String deviceSn;
    private String onlineStatus;

    /** 写入时刻归属门店（快照） */
    private Long storeSubjectId;
    private String storeName;

    /** 状态上报时间（报文 baseCreateTime） */
    private LocalDateTime baseCreateTime;

    /** 原始 payload（报文 JSON 文本，便于对方加字段后回溯；V78 新增列） */
    private String rawPayload;

    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}
