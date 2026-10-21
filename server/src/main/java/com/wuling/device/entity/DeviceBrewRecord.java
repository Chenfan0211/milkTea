package com.wuling.device.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 胶囊冲泡记录（设备队列上报）。
 *
 * <p>门店字段是【写入时刻快照】：MQ 只保证 at-least-once，设备也可能换绑门店，
 * 记录必须能还原「当时属于哪家门店」，故不跟随设备当前绑定变化。
 *
 * <p>唯一键 (device_sn, base_create_time, tea_code, serial_number) 用于幂等去重。
 */
@Data
@TableName("device_brew_record")
public class DeviceBrewRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String deviceSn;
    private String deviceName;

    /** 写入时刻归属门店（快照） */
    private Long storeSubjectId;
    private String storeName;

    private String teaCode;
    private String teaName;
    private String customerCode;
    private String madeDay;
    private String serialNumber;

    /** 冲泡时间（报文 baseCreateTime） */
    private LocalDateTime baseCreateTime;

    /** 原始 payload，便于追溯 */
    private String rawPayload;

    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}
