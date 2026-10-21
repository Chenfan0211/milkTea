package com.wuling.device.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设备与门店的绑定历史。
 *
 * <p>换绑 = 旧记录写 UNBIND + 新记录写 BIND（同一事务），因此历史可完整回溯
 * 「某台设备某段时间属于哪家门店」。门店名在此保存【快照】——
 * 门店改名后历史记录的归属仍应显示当时的名字。
 */
@Data
@TableName("device_store_binding")
public class DeviceStoreBinding {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long deviceId;
    private String deviceSn;
    private Long storeSubjectId;
    private String storeName;

    /** BIND 绑定 / UNBIND 解绑 */
    private String action;

    private LocalDateTime bindTime;
    private LocalDateTime unbindTime;

    /** 操作人（后台登录账号） */
    private String operator;

    /** 操作备注 / 解绑原因 */
    private String remark;

    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}
