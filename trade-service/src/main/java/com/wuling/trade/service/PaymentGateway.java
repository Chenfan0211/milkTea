package com.wuling.trade.service;

import com.wuling.trade.pay.wxpay.WxPayPrepayResult;

/**
 * 支付适配层：厂商确定前统一走 Mock 实现，接入真实通道时新增实现类即可。
 *
 * <p>第 14 期扩展：原 {@link #prepay(String, long)} 只返回一个字符串交易号，
 * 语义过窄 —— 小程序支付需要「prepay_id + 二次签名后的收银台参数」，
 * 故新增 {@link #prepayForMiniApp(String, long, String, String)}。
 * 保留旧方法以免破坏 Mock 通道与既有回归。
 *
 * <p><b>通道选型</b>：由 {@code app.pay.channel} 配置决定，
 * {@code mock}（默认）与 {@code wxpay} 两个实现共存，
 * 业务代码通过 {@link PaymentGatewayResolver} 获取，不直接依赖具体实现。
 */
public interface PaymentGateway {

    /** 渠道标识，如 WXPAY / MOCK */
    String channel();

    /** 发起支付（返回第三方交易号） */
    String prepay(String orderNo, long amount);

    /**
     * 小程序支付下单（第 14 期新增）。
     *
     * <p>返回「微信预支付会话标识 + 小程序唤起收银台参数」。
     * 默认实现委托 {@link #prepay} 以兼容尚未适配的通道，
     * 真实通道（微信支付）应重写本方法。
     *
     * @param orderNo     商户订单号（本项目即 orderNo）
     * @param amountFen   金额，单位<b>分</b>
     * @param payerOpenid 支付用户 openid（微信支付 JSAPI 必填）
     * @param description 商品描述（收银台展示，长度 1-127 字符）
     * @return 下单结果；通道不支持小程序支付时返回 null
     */
    default WxPayPrepayResult prepayForMiniApp(String orderNo, long amountFen,
                                               String payerOpenid, String description) {
        return null;
    }

    /**
     * 校验回调签名。
     *
     * <p>注意：微信支付 APIv3 的验签是「平台证书/公钥 + RSA-SHA256 + AES-GCM 解密」，
     * 与这里的「共享密钥 HMAC」语义不同，由
     * {@code WxPayNotifyService} 独立处理，<b>不复用本方法</b>。
     * 本方法保留给内部网关回调（{@code PaymentCallbackSigner}）。
     */
    boolean verifyCallback(String payload, String signature);
}
