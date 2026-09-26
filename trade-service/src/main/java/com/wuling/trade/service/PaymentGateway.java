package com.wuling.trade.service;

import com.wuling.trade.pay.wxpay.WxPayPrepayResult;

import java.time.LocalDateTime;

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
     * 小程序支付下单，并携带订单失效时间。
     *
     * <p>微信 JSAPI 下单会把 {@code time_expire} 透传到收银台；为空时保持旧行为。
     * 默认实现委托旧的四参数方法，兼容既有通道。
     */
    default WxPayPrepayResult prepayForMiniApp(String orderNo, long amountFen,
                                               String payerOpenid, String description,
                                               LocalDateTime expireTime) {
        return prepayForMiniApp(orderNo, amountFen, payerOpenid, description);
    }

    /**
     * 主动查询三方订单状态（补偿回调丢失。
     *
     * <p><b>为什么需要</b>：回调可能因网络抖动 / 通知地址不可用而丢失，
     * 此时用户已付款但系统仍停留在「支付中」。后台「异常重试」即调用本方法
     * 重新拉取三方真实状态，而不是把状态直接改成成功
     * （直接改状态等于伪造交易，会造成资金损失）。
     *
     * <p><b>失败语义</b>：查询失败时返回 {@code null}（网关不可用、订单不存在等），
     * 由调用方决定「保持原状态 + 提示」。刻意不抛异常，因为
     * 「查不到」是补偿流程的正常分支，不是系统故障。
     *
     * @param orderNo 商户订单号（本项目即 orderNo）
     * @return 交易状态；通道不支持查询或查询失败时返回 null
     */
    default PaymentQueryResult queryOrder(String orderNo) {
        return null;
    }

    /**
     * 三方订单查询结果。
     *
     * @param tradeState   三方交易状态原始值（如 SUCCESS / NOTPAY / CLOSED / PAYERROR）
     * @param tradeStateDesc 三方状态描述（用于后台展示与排查）
     * @param transactionId 三方订单号（流水订单号）
     * @param totalAmount  订单金额（分）
     */
    record PaymentQueryResult(String tradeState, String tradeStateDesc,
                              String transactionId, Long totalAmount) {
    }

    /**
     * 发起退款（微信支付 v3 退款下单）。
     *
     * <p><b>语义</b>：退款是异步的。返回 {@code accepted=true} 只代表「微信已受理」，
     * 不代表钱已到账；最终结果必须由退款结果通知回调（或主动查询）确认。
     *
     * <p><b>通道不支持退款时</b>返回 {@code null}（如 mock 通道），
     * 调用方据此决定是否跳过真实调用而直接落库。
     *
     * @param orderNo    原商户订单号
     * @param refundNo   商户退款单号（全局唯一，用于回调定位与幂等）
     * @param amountFen  退款金额（分）
     * @param reason     退款原因（选填）
     * @return 受理结果；通道不支持时返回 null
     */
    default RefundApplyResult refund(String orderNo, String refundNo, long amountFen, String reason) {
        return null;
    }

    /**
     * 退款下单受理结果。
     *
     * @param accepted      微信是否受理（true 已受理；false 同步失败）
     * @param thirdRefundNo 微信退款单号（受理成功时返回，可能为空）
     * @param failReason    同步失败原因（accepted=false 时非空）
     */
    record RefundApplyResult(boolean accepted, String thirdRefundNo, String failReason) {
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
