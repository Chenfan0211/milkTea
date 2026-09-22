package com.wuling.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 支付单。
 *
 * <p>第 14 期新增两个字段（见 {@code V15__payment_wxpay_fields.sql}）：
 * <ul>
 *   <li>{@code prepayId} —— 微信预支付会话标识，排查「起了收银台但没付成功」时必需；</li>
 *   <li>{@code payerOpenid} —— 支付者 openid，退款与对账必需
 *       （微信退款要求原路退回，需知道付款人）。</li>
 * </ul>
 */
@Data
@TableName("payment")
public class Payment {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String paymentNo;
    private Long orderId;
    private String orderNo;
    private Long amount;
    private String channel;
    private String thirdStatus;
    private String standardStatus;
    private String transactionId;
    private LocalDateTime callbackTime;

    /** 微信预支付会话标识（第 14 期新增） */
    private String prepayId;

    /** 支付者 openid（第 14 期新增） */
    private String payerOpenid;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
