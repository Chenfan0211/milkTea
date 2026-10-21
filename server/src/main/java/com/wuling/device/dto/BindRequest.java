package com.wuling.device.dto;

import lombok.Data;

/** 设备绑定/换绑门店入参。 */
@Data
public class BindRequest {

    /** 门店主体 biz_subject.id */
    private Long storeSubjectId;

    /** 操作备注（写入绑定历史） */
    private String remark;
}
