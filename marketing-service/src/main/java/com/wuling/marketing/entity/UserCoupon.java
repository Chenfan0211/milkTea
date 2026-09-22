package com.wuling.marketing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("user_coupon")
public class UserCoupon {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long couponId;
    private String status;
    private LocalDateTime receiveTime;
    private Long lockOrderId;
    private LocalDateTime useTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
