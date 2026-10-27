package com.wuling.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("orders")
public class Order {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private String clientRequestId;
    /** STORE=store order, MALL=online mall order. */
    private String orderType;
    private Long userId;
    private Long storeSubjectId;
    /** Split rule snapshot captured at order creation. */
    private Long splitRuleId;
    private String splitRuleSnapshot;
    private Long channelSubjectId;
    private String mealType;
    private LocalDateTime pickupTime;
    private String status;
    private String payStatus;
    /** 支付渠道：WXPAY / STORED_VALUE。 */
    private String payChannel;
    private String pickupCode;
    private Long totalAmount;
    private Long originalAmount;
    private Long discountAmount;
    private Long paidAmount;
    private Long couponId;
    private Long couponDiscount;
    /** 使用储值余额支付时产生的立减金额（分）。 */
    private Long storedValueDiscount;
    private Long pointsUsed;
    private Long pointsEarned;
    private String refundStatus;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime payTime;
    private LocalDateTime verifyTime;
    private LocalDateTime shippedTime;
    private LocalDateTime autoCompleteTime;
    private LocalDateTime completeTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
