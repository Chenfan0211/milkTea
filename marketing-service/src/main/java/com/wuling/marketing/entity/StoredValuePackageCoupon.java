package com.wuling.marketing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 储值套餐赠送券关联（{@code stored_value_package_coupon}）。
 *
 * <p>表在 {@code V2__schema_marketing.sql} 已建，此前一直没有对应实体 ——
 * 导致后台能配置赠券、但接口无法把它们下发到小程序。
 */
@Data
@TableName("stored_value_package_coupon")
public class StoredValuePackageCoupon {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long packageId;
    private Long couponId;
    private Integer count;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
