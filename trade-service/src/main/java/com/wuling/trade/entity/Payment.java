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

    /** 业务类型：点单订单支付（默认，兼容历史数据） */
    public static final String BIZ_ORDER = "ORDER";

    /** 业务类型：储值充值 */
    public static final String BIZ_STORED_VALUE = "STORED_VALUE";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String paymentNo;
    private Long orderId;
    private String orderNo;

    /** 业务类型：ORDER 订单支付 / STORED_VALUE 储值充值（第 15 期新增） */
    private String bizType;

    /** 业务单号：储值场景为 CZ 储值单号；订单场景同 orderNo（第 15 期新增） */
    private String bizNo;
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
