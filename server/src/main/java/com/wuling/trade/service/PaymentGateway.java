package com.wuling.trade.service;

/**
 * 支付适配层：厂商确定前统一走 Mock 实现，后续接入真实通道时新增实现类即可。
 */
public interface PaymentGateway {

    /** 渠道标识，如 WXPAY / MOCK */
    String channel();

    /** 发起支付（返回第三方交易号） */
    String prepay(String orderNo, long amount);

    /** 校验回调签名 */
    boolean verifyCallback(String payload, String signature);
}
