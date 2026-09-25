package com.wuling.trade.pay.wxpay;

import com.wuling.trade.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 微信支付结果通知接口（第 14 期支付接入）。
 *
 * <p><b>路径</b>：{@code POST /api/v1/app/payments/wxpay/notify}
 *
 * <p><b>为什么新增而不是复用 {@code /payments/callback}</b>：
 * <ul>
 *   <li>那一个用 HMAC 共享密钥验签，面向内部网关，协议不兼容；</li>
 *   <li>微信要求按规范应答（成功 {@code {"code":"SUCCESS"}}，
 *       失败 {@code {"code":"FAIL","message":"..."}}），
 *       与项目统一的 {@code Result} 结构完全不同；</li>
 *   <li>混用会导致验签失败与应答格式错误，且难以排查。</li>
 * </ul>
 *
 * <p><b>安全</b>：本接口无需 JWT（调用方是微信服务器，不是用户），
 * 由「验签 + 解密 + 金额比对 + 时间戳窗口」四重校验保护。
 * 网关白名单需放行该路径（已登记在 {@code GatewayAuthPolicy}）。
 *
 * <p><b>条件装配</b>：仅 {@code app.pay.channel=wxpay} 时注册，
 * 避免 mock 阶段对外暴露一个无验签能力的公网接口。
 */
@RestController
@RequestMapping("/api/v1/app/payments/wxpay")
@ConditionalOnProperty(name = "app.pay.wxpay.channel", havingValue = "wxpay")
public class WxPayNotifyController {

    private static final Logger log = LoggerFactory.getLogger(WxPayNotifyController.class);

    private final WxPayNotifyService notifyService;
    private final PaymentService paymentService;

    public WxPayNotifyController(WxPayNotifyService notifyService, PaymentService paymentService) {
        this.notifyService = notifyService;
        this.paymentService = paymentService;
    }

    @PostMapping("/notify")
    public ResponseEntity<Map<String, String>> notify(
            @RequestHeader(value = "Wechatpay-Serial", required = false) String serial,
            @RequestHeader(value = "Wechatpay-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "Wechatpay-Nonce", required = false) String nonce,
            @RequestHeader(value = "Wechatpay-Signature", required = false) String signature,
            @RequestHeader(value = "Wechatpay-Signature-Type", required = false) String signatureType,
            @RequestBody(required = false) String body) {

        WxPayTransaction transaction;
        try {
            // 注意：body 必须是原始报文，Spring 以 String 接收可保证零改动
            transaction = notifyService.verifyAndDecrypt(serial, timestamp, nonce, signature, body);
        } catch (WxPayNotifyService.WxPayNotifyException e) {
            log.warn("微信支付回调校验失败：{}", e.getMessage());
            return fail(e.getMessage());
        }

        try {
            // 只处理「支付成功」事件；其他事件按规范应答 SUCCESS，避免微信无意义重试
            if (!WxPayNotifyService.EVENT_TRANSACTION_SUCCESS.equals(transaction.getEventType())) {
                log.info("微信支付回调事件非支付成功，已忽略 eventType={} outTradeNo={}",
                        transaction.getEventType(), transaction.getOutTradeNo());
                return success();
            }

            if (!WxPayStatusMapper.isSuccess(transaction.getTradeState())) {
                log.info("微信支付回调交易状态非成功，已忽略 tradeState={} outTradeNo={}",
                        transaction.getTradeState(), transaction.getOutTradeNo());
                return success();
            }

            // 按单号前缀路由：订单支付走 handleWxPayCallback，
            // 储值充值走 handleStoredValueCallback（第 15 期）。
            // 此前直接调 handleWxPayCallback 会让储值回调因查不到订单而失败。
            paymentService.routeWxPayCallback(transaction);
            return success();
        } catch (Exception e) {
            // 入账失败应答 FAIL，让微信按策略重试；
            // 幂等由 PaymentService 的订单行锁 + 状态机保证，重试不会重复入账
            log.error("微信支付回调入账失败 outTradeNo={} err={}",
                    transaction.getOutTradeNo(), e.getMessage(), e);
            return fail("回调处理失败");
        }
    }

    /** 成功应答：微信要求「无任何多余字段」的 {"code":"SUCCESS"} */
    private ResponseEntity<Map<String, String>> success() {
        return ResponseEntity.ok(Map.of("code", "SUCCESS"));
    }

    /** 失败应答：微信会按策略重试；message 不回显敏感信息 */
    private ResponseEntity<Map<String, String>> fail(String message) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", "FAIL", "message", message == null ? "处理失败" : message));
    }
}
