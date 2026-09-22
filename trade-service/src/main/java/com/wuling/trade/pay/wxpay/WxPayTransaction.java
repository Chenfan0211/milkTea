package com.wuling.trade.pay.wxpay;

import lombok.Data;

/**
 * 微信支付订单查询结果（第 14 期支付接入）。
 *
 * <p>用于两处：
 * <ol>
 *   <li><b>支付结果通知</b>解密后的报文（字段与查询接口一致）；</li>
 *   <li><b>主动查询补偿</b>：回调丢失时由定时任务轮询微信订单状态。</li>
 * </ol>
 *
 * <p>金额单位一律为<b>分</b>，与项目内部口径一致。
 */
@Data
public class WxPayTransaction {

    /** 商户订单号（即本项目的 orderNo） */
    private String outTradeNo;

    /** 微信支付订单号 */
    private String transactionId;

    /** 交易状态：SUCCESS / REFUND / NOTPAY / CLOSED / REVOKED / USERPAYING / PAYERROR */
    private String tradeState;

    /** 交易状态描述 */
    private String tradeStateDesc;

    /** 订单总金额（分） */
    private Long totalAmount;

    /** 用户支付金额（分） */
    private Long payerTotal;

    /** 支付者 openid */
    private String payerOpenid;

    /** 商户号 */
    private String mchId;

    /** AppID */
    private String appId;

    /** 支付完成时间（形如 2018-06-08T10:34:56+08:00） */
    private String successTime;

    /** 事件类型（回调场景：TRANSACTION.SUCCESS），查询场景为 null */
    private String eventType;

    /** 微信通知 ID（回调场景有值），用于排查与幂等辅助 */
    private String notifyId;
}
