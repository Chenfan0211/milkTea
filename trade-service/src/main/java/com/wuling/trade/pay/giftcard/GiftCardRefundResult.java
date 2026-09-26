package com.wuling.trade.pay.giftcard;

/** 礼品卡退款受理结果。 */
public record GiftCardRefundResult(String refundNo, Long amount, String status) {
}
