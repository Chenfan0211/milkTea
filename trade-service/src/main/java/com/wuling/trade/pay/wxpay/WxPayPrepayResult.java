package com.wuling.trade.pay.wxpay;

import lombok.Data;

/**
 * 统一下单结果（第 14 期支付接入）。
 *
 * <p>把「微信侧返回的 prepay_id」与「给小程序唤起收银台的参数」放在一起返回：
 * <ul>
 *   <li>{@code prepayId} 需要落库到 {@code payment.prepay_id}，便于排查与对账；</li>
 *   <li>{@code params} 是响应给小程序的部分，不落库（含签名字段）。</li>
 * </ul>
 */
@Data
public class WxPayPrepayResult {

    /** 微信预支付会话标识 */
    private String prepayId;

    /** 小程序支付参数 */
    private WxPayParams params;
}
