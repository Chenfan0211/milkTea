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
    /** 来源：RECEIVE领券/POINTS_EXCHANGE积分兑换/STORED_VALUE储值赠券 */
    private String source;
    /** 发放幂等键：普通渠道 SINGLE，储值赠券 SV:订单号:券ID:序号 */
    private String grantNo;
    private LocalDateTime receiveTime;
    private Long lockOrderId;
    private LocalDateTime useTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
