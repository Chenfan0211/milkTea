package com.wuling.trade.pay.wxpay;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 小程序 {@code wx.requestPayment} 所需的支付参数（第 14 期支付接入）。
 *
 * <p><b>字段名必须与微信小程序端约定完全一致</b>：{@code timeStamp}（驼峰 T、S 大写）、
 * {@code nonceStr}、{@code package}、{@code signType}、{@code paySign}。
 * 前四个以外的任何拼写都会导致收银台唤起失败，故此处用
 * {@link JsonProperty} 显式钉住字段名，不依赖全局 Jackson 命名策略。
 *
 * <p>{@code package} 在 Java 里是保留字，无法作为字段名，
 * 因此内部字段叫 {@code packageValue}，对外仍序列化为 {@code package}。
 *
 * <p>本对象由微信官方 SDK 的
 * {@code PrepayWithRequestPaymentResponse} 转换而来，
 * 其中 {@code paySign} 是 SDK 用商户私钥做的<b>二次签名</b>
 * （签名串：appId + "\n" + timeStamp + "\n" + nonceStr + "\n" + package + "\n"）。
 */
@Data
public class WxPayParams {

    /** 小程序 AppID */
    @JsonProperty("appId")
    private String appId;

    /** 时间戳（秒，字符串） */
    @JsonProperty("timeStamp")
    private String timeStamp;

    /** 随机字符串 */
    @JsonProperty("nonceStr")
    private String nonceStr;

    /** 订单详情扩展字符串，形如 prepay_id=wx22... */
    @JsonProperty("package")
    private String packageValue;

    /** 签名方式，APIv3 固定为 RSA */
    @JsonProperty("signType")
    private String signType;

    /** 签名值 */
    @JsonProperty("paySign")
    private String paySign;
}
