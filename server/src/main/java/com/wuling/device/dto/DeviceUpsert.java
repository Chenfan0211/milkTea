package com.wuling.device.dto;

import lombok.Data;

/**
 * 设备新增/编辑入参。
 *
 * <p>在线状态与最近上报时间由队列消息维护，不在此表单内 ——
 * 手工改动会让台账与设备真实状态不一致。
 */
@Data
public class DeviceUpsert {

    /** 设备编码（必填，活动台账中唯一；仅允许字母、数字、点、下划线、短横线） */
    private String deviceSn;

    /** 设备ID（必填，活动台账中唯一；仅允许字母、数字、点、下划线、短横线） */
    private String deviceName;

    /** 运营别名 */
    private String alias;

    private String remark;

    /** 1启用 0停用 */
    private Integer enabled;
}
