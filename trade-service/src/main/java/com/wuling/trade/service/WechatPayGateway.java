package com.wuling.trade.service;

import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.Amount;
import com.wechat.pay.java.service.payments.jsapi.model.Payer;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayRequest;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayWithRequestPaymentResponse;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.trade.pay.wxpay.WxPayParams;
import com.wuling.trade.pay.wxpay.WxPayPrepayResult;
import com.wuling.trade.pay.wxpay.WxPayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 微信支付通道实现（第 14 期支付接入）。
 *
 * <p><b>当前状态：代码已就绪，但默认不生效。</b>
 * 本类由 {@code app.pay.channel=wxpay} 条件装配。
 * 默认 {@code mock} 通道下本 Bean 不会创建，
 * 因此不会读取证书、不会向微信发起任何请求。
 * 待域名备案完成、https 回调地址可用后，仅需切换配置即可启用。
 *
 * <p><b>为什么用官方 SDK 的 {@code JsapiServiceExtension}</b>：
 * 它一次性完成「统一下单 + 小程序二次签名」，直接返回
 * {@code timeStamp/nonceStr/package/signType/paySign}。
 * 自己拼装这五个字段需要正确处理签名串的换行与末尾换行，
 * 出错表现为「收银台唤起失败」，且难以定位，故交给官方实现。
 *
 * <p><b>金额口径</b>：微信以「分」为单位，项目内部也是「分」，
 * 因此此处<b>不做任何单位换算</b>，直接透传 {@code amountFen}。
 */
@Component
@ConditionalOnProperty(name = "app.pay.wxpay.channel", havingValue = "wxpay")
public class WechatPayGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(WechatPayGateway.class);

    /** 微信侧商品描述最大长度 */
    private static final int MAX_DESCRIPTION_LENGTH = 127;

    private final WxPayProperties properties;
    private final JsapiServiceExtension jsapiService;

    public WechatPayGateway(WxPayProperties properties, JsapiServiceExtension jsapiService) {
        this.properties = properties;
        this.jsapiService = jsapiService;
    }

    @Override
    public String channel() {
        return "WXPAY";
    }

    /**
     * 旧接口：微信支付没有「仅返回交易号」的语义，故不支持。
     *
     * <p>刻意抛异常而非返回假单号：静默返回占位串会让上游以为下单成功，
     * 掩盖真实的通道选型错误。
     */
    @Override
    public String prepay(String orderNo, long amount) {
        throw new BusinessException(ResultCode.BAD_REQUEST,
                "微信支付通道不支持该调用方式，请使用小程序支付接口");
    }

    @Override
    public WxPayPrepayResult prepayForMiniApp(String orderNo, long amountFen,
                                              String payerOpenid, String description) {
        if (!StringUtils.hasText(payerOpenid)) {
            // 不静默降级：缺 openid 说明用户登录态异常，下单必然被微信拒绝
            throw new BusinessException(ResultCode.UNAUTHORIZED, "登录状态异常，请重新登录后再支付");
        }
        if (amountFen <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "支付金额必须大于 0");
        }

        PrepayRequest request = new PrepayRequest();
        request.setAppid(properties.getAppId());
        request.setMchid(properties.getMchId());
        request.setDescription(buildDescription(description));
        request.setOutTradeNo(orderNo);
        request.setNotifyUrl(properties.getNotifyUrl());

        Amount amount = new Amount();
        amount.setTotal(Math.toIntExact(amountFen));
        amount.setCurrency("CNY");
        request.setAmount(amount);

        Payer payer = new Payer();
        payer.setOpenid(payerOpenid);
        request.setPayer(payer);

        try {
            PrepayWithRequestPaymentResponse response = jsapiService.prepayWithRequestPayment(request);

            WxPayParams params = new WxPayParams();
            params.setAppId(response.getAppId());
            params.setTimeStamp(response.getTimeStamp());
            params.setNonceStr(response.getNonceStr());
            params.setPackageValue(response.getPackageVal());
            params.setSignType(response.getSignType());
            params.setPaySign(response.getPaySign());

            WxPayPrepayResult result = new WxPayPrepayResult();
            result.setPrepayId(extractPrepayId(response.getPackageVal()));
            result.setParams(params);

            // 日志脱敏：只记录订单号与 prepay 前缀，不记录完整签名与 openid
            log.info("微信支付统一下单成功 orderNo={} amount={} prepayId={}",
                    orderNo, amountFen, mask(result.getPrepayId()));
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 不向上暴露微信原始报文（可能含敏感信息），但保留本地日志便于排查
            log.error("微信支付统一下单失败 orderNo={} err={}", orderNo, e.getMessage(), e);
            throw new BusinessException(ResultCode.ERROR, "发起支付失败，请稍后重试");
        }
    }

    @Override
    public boolean verifyCallback(String payload, String signature) {
        // 微信支付 APIv3 回调验签由 WxPayNotifyService 使用平台证书/公钥完成，
        // 不复用本方法（语义为内部网关的共享密钥 HMAC）
        throw new UnsupportedOperationException(
                "微信支付回调验签请使用 WxPayNotifyService，而非 PaymentGateway#verifyCallback");
    }

    /** 组装商品描述，兜底前缀并截断到微信限制长度 */
    private String buildDescription(String description) {
        String text = StringUtils.hasText(description) ? description.trim() : "订单支付";
        String prefix = StringUtils.hasText(properties.getDescriptionPrefix())
                ? properties.getDescriptionPrefix() : "";
        String full = prefix + text;
        return full.length() <= MAX_DESCRIPTION_LENGTH
                ? full
                : full.substring(0, MAX_DESCRIPTION_LENGTH);
    }

    /** 从 package（prepay_id=xxx）中取出 prepay_id；取不到时返回原串 */
    private String extractPrepayId(String packageValue) {
        if (!StringUtils.hasText(packageValue)) {
            return null;
        }
        int idx = packageValue.indexOf('=');
        return idx >= 0 ? packageValue.substring(idx + 1) : packageValue;
    }

    /** 脱敏：只保留前缀，避免完整 prepay_id 进日志 */
    private String mask(String value) {
        if (!StringUtils.hasText(value)) {
            return "-";
        }
        return value.length() <= 12 ? value : value.substring(0, 12) + "***";
    }
}
