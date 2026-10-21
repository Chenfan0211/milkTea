package com.wuling.device.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 茶机设备台账。
 *
 * <p>设备由「队列上报」自动建档（未绑定门店），运营再在后台绑定门店；
 * 一台设备同一时刻只归属一家门店，一家门店可以绑定多台设备。
 */
@Data
@TableName("device_info")
public class DeviceInfo {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 设备编码（报文 deviceSN），设备唯一标识 */
    private String deviceSn;

    /** 设备ID（报文 deviceName） */
    private String deviceName;

    /** 运营别名 */
    private String alias;

    private String remark;

    /** 1启用 0停用（运营维护，停用不影响消息落库） */
    private Integer enabled;

    /** ONLINE / OFFLINE，由队列消息维护，运营不可改 */
    private String onlineStatus;

    /** 最近一次上报时间（报文 baseCreateTime） */
    private LocalDateTime lastReportTime;

    /** 最近一次状态变更时间 */
    private LocalDateTime lastStatusChangeTime;

    /** 当前归属门店 biz_subject.id，NULL=未绑定 */
    private Long storeSubjectId;

    /** 当前绑定生效时间 */
    private LocalDateTime bindTime;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
