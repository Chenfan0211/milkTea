package com.wuling.device.dto;

import lombok.Data;

/** 设备解绑门店入参。 */
@Data
public class UnbindRequest {

    /** 解绑原因（写入绑定历史，便于追责） */
    private String reason;
}
