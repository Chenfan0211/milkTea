package com.wuling.trade.pay.yeepay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeepay.yop.sdk.service.common.response.YopResponse;
import com.wuling.trade.pay.wxpay.WxPayParams;
import com.wuling.trade.pay.wxpay.WxPayPrepayResult;
import com.wuling.trade.service.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 易宝收款通道（微信小程序支付）。
 *
 * <p><b>两步下单，无法省略</b>：易宝官方《小程序支付》指引明确
 * 「小程序支付对接聚合统一下单接口」，且「小程序内只能使用小程序支付」。
 *
 * <ol>
 *   <li>{@code trade/order} —— 建单，传 {@code fundProcessType=DELAY_SETTLE}
 *       让钱进「待分账账户」（这样才能后续分账给经营者）；返回 token</li>
 *   <li>{@code aggpay/pre-pay} —— 用 token 换小程序唤起参数；
 *       {@code payWay=MINI_PROGRAM} + {@code userIp}（必填）</li>
 * </ol>
 *
 * <p><b>与微信的关键差异</b>：微信直接返回 prepay_id + 二次签名参数；
 * 易宝第二步返回的也是小程序唤起参数，字段名一致（appId/timeStamp/
 * nonceStr/package/signType/paySign），故前端可复用同一套
 * {@code wx.requestPayment} 调用。
 *
 * <p><b>条件装配</b>：仅 {@code app.pay.yeepay.channel=yeepay} 时创建。
 * 未启用时本类不存在，微信链路完全不受影响。
 */
@Component
@ConditionalOnProperty(name = "app.pay.yeepay.channel", havingValue = "yeepay")
public class YeepayPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(YeepayPaymentGateway.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String PATH_ORDER = "/rest/v1.0/trade/order";
    private static final String PATH_PREPAY = "/rest/v1.0/aggpay/pre-pay";
    private static final String PATH_REFUND = "/rest/v1.0/trade/refund";

    private final YeepayApiClient apiClient;
    private final YeepayProperties properties;
    private final ObjectMapper objectMapper;
    private final String appId;

    public YeepayPaymentGateway(YeepayApiClient apiClient,
                                YeepayProperties properties,
                                ObjectMapper objectMapper,
                                @Value("${app.wx.app-id:}") String appId) {
        this.apiClient = apiClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.appId = appId;
    }

    @Override
    public String channel() {
        return "YEEPAY";
    }

    /** 旧接口：仅返回 token，保留兼容（真实支付走 prepayForMiniApp）。 */
    @Override
    public String prepay(String orderNo, long amount) {
        try {
            String token = createOrder(orderNo, amount, null);
            return token == null ? null : "YEEPAY-" + token;
        } catch (Exception e) {
            log.error("易宝下单失败 orderNo={} err={}", orderNo, e.getMessage());
            return null;
        }
    }

    @Override
    public WxPayPrepayResult prepayForMiniApp(String orderNo, long amountFen,
                                              String payerOpenid, String description) {
        return prepayForMiniApp(orderNo, amountFen, payerOpenid, description, null, null);
    }

    @Override
    public WxPayPrepayResult prepayForMiniApp(String orderNo, long amountFen,
                                              String payerOpenid, String description,
                                              LocalDateTime expireTime) {
        return prepayForMiniApp(orderNo, amountFen, payerOpenid, description, expireTime, null);
    }

    /**
     * 小程序下单（两步）。
     *
     * @param clientIp 用户真实 IP，{@code aggpay/pre-pay} 必填；
     *                 由调用方从请求头解析后传入
     */
    public WxPayPrepayResult prepayForMiniApp(String orderNo, long amountFen,
                                              String payerOpenid, String description,
                                              LocalDateTime expireTime, String clientIp) {
        if (appId == null || appId.isBlank()) {
            log.error("易宝小程序支付缺少 appId 配置（app.wx.app-id）");
            return null;
        }
        if (payerOpenid == null || payerOpenid.isBlank()) {
            log.error("易宝小程序支付缺少 openid orderNo={}", orderNo);
            return null;
        }
        // userIp 是 aggpay/pre-pay 的必填项，缺失时下单必失败
        String userIp = clientIp == null || clientIp.isBlank() ? "127.0.0.1" : clientIp;

        try {
            // 步骤一：建单，标记延迟分账
            String token = createOrder(orderNo, amountFen, expireTime);
            if (token == null) {
                return null;
            }
            // 步骤二：用 token 换唤起参数
            return prepayWithToken(orderNo, token, payerOpenid, description, userIp);
        } catch (Exception e) {
            log.error("易宝小程序下单异常 orderNo={} err={}", orderNo, e.getMessage());
            return null;
        }
    }

    /**
     * 步骤一：{@code trade/order} 建单。
     *
     * <p>关键参数 {@code fundProcessType=DELAY_SETTLE}：
     * 钱进「待分账账户」而非平台资金账户，这是后续分账给经营者的前提。
     */
    private String createOrder(String orderNo, long amountFen, LocalDateTime expireTime) {
        Map<String, String> params = new HashMap<>();
        params.put("parentMerchantNo", properties.getMerchantNo());
        params.put("merchantNo", properties.getPlatformMerchantNo());
        params.put("orderId", orderNo);
        params.put("orderAmount", fenToYuan(amountFen));
        params.put("goodsName", "五零时光");
        // 延迟分账：钱进待分账账户，等待 divide/apply
        params.put("fundProcessType", "DELAY_SETTLE");
        params.put("notifyUrl", properties.notifyUrl("/api/v1/app/yeepay/notify/pay"));
        // 清算回调：清算成功后才能分账/退款
        params.put("csUrl", properties.notifyUrl("/api/v1/app/yeepay/notify/clearing"));
        if (expireTime != null) {
            params.put("expiredTime", expireTime.format(FMT));
        }
        YopResponse response = apiClient.post(PATH_ORDER, params);
        JsonNode root = parse(response);
        String code = text(root, "code");
        if (!"OPR00000".equals(code)) {
            log.warn("易宝建单失败 orderNo={} code={} msg={}",
                    orderNo, code, text(root, "message"));
            return null;
        }
        String token = text(root, "token");
        if (token == null || token.isBlank()) {
            log.error("易宝建单未返回 token orderNo={}", orderNo);
            return null;
        }
        log.info("易宝建单成功 orderNo={} uniqueOrderNo={}", orderNo, text(root, "uniqueOrderNo"));
        return token;
    }

    /** 步骤二：{@code aggpay/pre-pay} 用 token 换小程序唤起参数。 */
    private WxPayPrepayResult prepayWithToken(String orderNo, String token,
                                              String openid, String description, String userIp) {
        Map<String, String> params = new HashMap<>();
        // 传了 token 可省略 merchantNo / orderId / orderAmount
        params.put("token", token);
        params.put("payWay", "MINI_PROGRAM");
        params.put("channel", "WECHAT");
        params.put("scene", "ONLINE");
        params.put("appId", appId);
        params.put("userId", openid);
        params.put("userIp", userIp);
        params.put("goodsName", description == null ? "五零时光" : description);

        YopResponse response = apiClient.post(PATH_PREPAY, params);
        JsonNode root = parse(response);
        String code = text(root, "code");
        if (!"OPR00000".equals(code) && !"00000".equals(code)) {
            log.warn("易宝预下单失败 orderNo={} code={} msg={}",
                    orderNo, code, text(root, "message"));
            return null;
        }

        // 易宝不同接口的字段大小写不统一（appId / appid、paySign / sign），
        // 这里全部做多候选匹配，避免联调时因大小写差异白跑一轮。
        // 若易宝实际返回的字段名与候选都不符，会在下方日志中暴露，便于对齐。
        WxPayParams payParams = new WxPayParams();
        payParams.setAppId(firstText(root, "appId", "appid", "app_id"));
        payParams.setTimeStamp(firstText(root, "timeStamp", "timestamp", "time_stamp"));
        payParams.setNonceStr(firstText(root, "nonceStr", "noncestr", "nonce_str"));
        payParams.setPackageValue(firstText(root, "package", "packageValue", "prepayPackage"));
        payParams.setSignType(firstText(root, "signType", "signtype", "sign_type"));
        payParams.setPaySign(firstText(root, "paySign", "pay_sign", "sign"));

        // 关键字段缺失时记录完整响应（脱敏后），供联调时精确对齐字段名。
        // 不在这里抛异常：让上层按「通道不支持小程序支付」处理，
        // 用户看到的是友好提示，运维从日志里拿到排查依据。
        if (payParams.getPaySign() == null || payParams.getPackageValue() == null
                || payParams.getTimeStamp() == null) {
            log.error("易宝预下单响应缺少小程序唤起参数 orderNo={} 响应={} "
                            + "（需要 timeStamp/nonceStr/package/signType/paySign）",
                    orderNo, root.toString());
        }

        WxPayPrepayResult result = new WxPayPrepayResult();
        result.setPrepayId(text(root, "prepayId"));
        result.setParams(payParams);
        return result;
    }

    /**
     * 退款（带归集明细）。
     *
     * <p>{@code divideBackDetail} 由 server 侧按分账进度构造：
     * 已分账的传明细分账单号归集，未分账的从待分账账户扣。
     * 该参数为 null 时表示整单未分账，直接从待分账账户退。
     */
    @Override
    public RefundApplyResult refund(String orderNo, String refundNo, long refundAmountFen,
                                    long originalTotalFen, String reason,
                                    List<Map<String, Object>> divideBackDetail) {
        Map<String, String> params = new HashMap<>();
        params.put("parentMerchantNo", properties.getMerchantNo());
        params.put("merchantNo", properties.getPlatformMerchantNo());
        params.put("orderId", orderNo);
        params.put("refundRequestId", refundNo);
        params.put("refundAmount", fenToYuan(refundAmountFen));
        if (reason != null && !reason.isBlank()) {
            params.put("description", reason);
        }
        params.put("notifyUrl", properties.notifyUrl("/api/v1/app/yeepay/notify/refund"));
        if (divideBackDetail != null && !divideBackDetail.isEmpty()) {
            params.put("divideBackDetail", toJson(divideBackDetail));
        }
        try {
            YopResponse response = apiClient.post(PATH_REFUND, params);
            JsonNode root = parse(response);
            String code = text(root, "code");
            String status = text(root, "status");
            if (!"OPR00000".equals(code)) {
                String message = text(root, "message");
                log.warn("易宝退款未受理 orderNo={} refundNo={} code={} msg={}",
                        orderNo, refundNo, code, message);
                // 归集不足类错误：平台需垫付重试（由上层决定）
                boolean collectFailed = isCollectFailure(code, message);
                return new RefundApplyResult(false, null,
                        message == null ? "退款失败（" + code + "）" : message, collectFailed);
            }
            return new RefundApplyResult(true, text(root, "uniqueRefundNo"), null, false);
        } catch (Exception e) {
            log.error("易宝退款调用异常 orderNo={} refundNo={} err={}",
                    orderNo, refundNo, e.getMessage());
            // unknown=true：请求可能已到达易宝，不能直接判失败
            return new RefundApplyResult(false, null, "退款请求异常：" + e.getMessage(), true);
        }
    }

    @Override
    public String queryRefund(String orderNo, String refundNo) {
        Map<String, String> params = new HashMap<>();
        params.put("parentMerchantNo", properties.getMerchantNo());
        params.put("merchantNo", properties.getPlatformMerchantNo());
        params.put("orderId", orderNo);
        params.put("refundRequestId", refundNo);
        try {
            YopResponse response = apiClient.get("/rest/v1.0/trade/refund/query", params);
            JsonNode root = parse(response);
            return text(root, "status");
        } catch (Exception e) {
            log.warn("查询易宝退款失败 orderNo={} refundNo={} err={}",
                    orderNo, refundNo, e.getMessage());
            return null;
        }
    }

    /**
     * 是否为「归集不足」类错误。
     *
     * <p>命中时上层应改用 {@code refundAccountType=FUND_ACCOUNT}
     * （平台账户垫付）重试，并记对账异常向经营者追偿。
     */
    private boolean isCollectFailure(String code, String message) {
        if ("OPR21033".equals(code) || "OPR26020".equals(code)) {
            // 归集金额超限 / 账户余额不足
            return true;
        }
        if (message == null) {
            return false;
        }
        return message.contains("归集") || message.contains("余额不足")
                || message.contains("分账退回金额超限");
    }

    @Override
    public boolean verifyCallback(String payload, String signature) {
        // 易宝回调走数字信封解密（见 YeepayNotifyService），不复用共享密钥模式
        return false;
    }

    // ---------------- 工具 ----------------

    private String fenToYuan(long fen) {
        return String.format("%.2f", fen / 100.0);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("易宝报文序列化失败", e);
        }
    }

    private JsonNode parse(YopResponse response) {
        Object result = response.getResult();
        if (result == null) {
            return objectMapper.createObjectNode();
        }
        try {
            if (result instanceof String text) {
                return objectMapper.readTree(text);
            }
            return objectMapper.valueToTree(result);
        } catch (Exception e) {
            log.warn("解析易宝响应失败 err={}", e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    /** 字段名在不同易宝接口上有大小写差异，取首个非空。 */
    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
