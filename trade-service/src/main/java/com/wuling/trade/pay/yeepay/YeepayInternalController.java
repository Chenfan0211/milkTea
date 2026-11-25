package com.wuling.trade.pay.yeepay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 易宝相关内部接口（供 server 调用）。
 *
 * <p><b>为什么放在 trade-service</b>：易宝 SDK 与密钥只存在于本模块，
 * server 完全不知道易宝报文格式。这与微信支付的分工一致。
 *
 * <p><b>安全</b>：{@code /internal/**} 不经网关路由，各服务仅监听 127.0.0.1。
 */
@RestController
@RequestMapping("/internal/yeepay")
@ConditionalOnProperty(name = "app.pay.yeepay.channel", havingValue = "yeepay")
public class YeepayInternalController {

    private static final Logger log = LoggerFactory.getLogger(YeepayInternalController.class);

    private final YeepayOnboardService onboardService;
    private final YeepaySettleCardService settleCardService;
    private final YeepayDivideService divideService;
    private final YeepaySettleService settleService;
    private final YeepayProperties properties;

    public YeepayInternalController(YeepayOnboardService onboardService,
                                    YeepaySettleCardService settleCardService,
                                    YeepayDivideService divideService,
                                    YeepaySettleService settleService,
                                    YeepayProperties properties) {
        this.onboardService = onboardService;
        this.settleCardService = settleCardService;
        this.divideService = divideService;
        this.settleService = settleService;
        this.properties = properties;
    }

    /** 提交子商户入驻。 */
    @PostMapping("/onboard")
    public Map<String, Object> onboard(@RequestBody Map<String, Object> body) {
        YeepayOnboardService.OnboardInput input = new YeepayOnboardService.OnboardInput(
                str(body, "requestNo"), str(body, "signType"), str(body, "legalName"),
                str(body, "legalLicenceNo"), str(body, "legalLicenceFrontUrl"),
                str(body, "legalLicenceBackUrl"), str(body, "licenceUrl"), str(body, "licenceNo"),
                str(body, "signName"), str(body, "shortName"), str(body, "contactName"),
                str(body, "contactMobile"), str(body, "contactEmail"),
                str(body, "province"), str(body, "city"), str(body, "district"), str(body, "address"),
                str(body, "bankAccountType"), str(body, "bankCardNo"), str(body, "bankCode"));
        YeepayOnboardService.OnboardResult result = onboardService.submit(input);
        return Map.of(
                "accepted", result.accepted(),
                "applicationNo", nvl(result.applicationNo()),
                "merchantNo", nvl(result.merchantNo()),
                "applicationStatus", nvl(result.applicationStatus()),
                "failReason", nvl(result.failReason()));
    }

    /** 查询入驻进度。 */
    @PostMapping("/onboard/query")
    public Map<String, Object> onboardQuery(@RequestBody Map<String, Object> body) {
        YeepayOnboardService.OnboardQueryResult result = onboardService.query(str(body, "requestNo"));
        if (result == null) {
            return Map.of("applicationStatus", "", "merchantNo", "", "rejectReason", "");
        }
        return Map.of(
                "applicationStatus", nvl(result.applicationStatus()),
                "merchantNo", nvl(result.merchantNo()),
                "rejectReason", nvl(result.rejectReason()));
    }

    /** 绑定结算卡。 */
    @PostMapping("/settle-card")
    public Map<String, Object> settleCard(@RequestBody Map<String, Object> body) {
        YeepaySettleCardService.BindResult result = settleCardService.bind(
                str(body, "merchantNo"), str(body, "bankCardType"), str(body, "accountNo"),
                str(body, "bankCode"), str(body, "branchCode"));
        return Map.of(
                "accepted", result.accepted(),
                "settleCardId", nvl(result.settleCardId()),
                "failReason", nvl(result.failReason()));
    }

    /** 订单分账。 */
    @PostMapping("/divide")
    @SuppressWarnings("unchecked")
    public Map<String, Object> divide(@RequestBody Map<String, Object> body) {
        List<Map<String, Object>> raw = (List<Map<String, Object>>) body.get("receivers");
        List<YeepayDivideService.DivideReceiver> receivers = raw == null ? List.of()
                : raw.stream()
                .map(item -> new YeepayDivideService.DivideReceiver(
                        String.valueOf(item.get("ledgerNo")),
                        ((Number) item.get("amountFen")).longValue(),
                        item.get("description") == null ? null : String.valueOf(item.get("description"))))
                .toList();
        YeepayDivideService.DivideResult result = divideService.apply(
                str(body, "orderId"), resolveMerchantNo(body),
                str(body, "divideRequestId"), receivers);
        // 透传明细：调用方需要 divideDetailNo 回写结算记录，
        // 否则部分分账场景无法做退款归集。
        List<Map<String, Object>> details = result.details().stream()
                .map(d -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("ledgerNo", nvl(d.ledgerNo()));
                    item.put("divideDetailNo", nvl(d.divideDetailNo()));
                    item.put("amount", nvl(d.amount()));
                    item.put("status", nvl(d.status()));
                    return item;
                })
                .toList();
        Map<String, Object> response = new HashMap<>();
        response.put("accepted", result.accepted());
        response.put("status", nvl(result.status()));
        response.put("uniqueDivideNo", nvl(result.uniqueDivideNo()));
        response.put("failReason", nvl(result.failReason()));
        response.put("details", details);
        return response;
    }

    /** 完结分账（平台份额落回余额）。 */
    @PostMapping("/divide/complete")
    public Map<String, Object> divideComplete(@RequestBody Map<String, Object> body) {
        YeepayDivideService.DivideResult result = divideService.complete(
                str(body, "orderId"), resolveMerchantNo(body));
        return Map.of(
                "accepted", result.accepted(),
                "status", nvl(result.status()),
                "failReason", nvl(result.failReason()));
    }

    /**
     * 分账资金归还（退款前调用）。
     *
     * <p>订单已分账给经营者后要退款，必须先归还会被退款覆盖的那部分资金，
     * 否则等于平台替经营者垫付退款。
     */
    @PostMapping("/divide/back")
    @SuppressWarnings("unchecked")
    public Map<String, Object> divideBack(@RequestBody Map<String, Object> body) {
        List<Map<String, Object>> raw = (List<Map<String, Object>>) body.get("receivers");
        List<YeepayDivideService.DivideReceiver> receivers = raw == null ? List.of()
                : raw.stream()
                .map(item -> new YeepayDivideService.DivideReceiver(
                        String.valueOf(item.get("ledgerNo")),
                        ((Number) item.get("amountFen")).longValue(),
                        item.get("description") == null ? null : String.valueOf(item.get("description"))))
                .toList();
        YeepayDivideService.DivideResult result = divideService.divideBack(
                str(body, "orderId"), resolveMerchantNo(body),
                str(body, "divideBackRequestId"), receivers);
        return Map.of(
                "accepted", result.accepted(),
                "status", nvl(result.status()),
                "failReason", nvl(result.failReason()));
    }

    /** 查询分账结果。 */
    @PostMapping("/divide/query")
    public Map<String, Object> divideQuery(@RequestBody Map<String, Object> body) {
        YeepayDivideService.DivideQueryResult result = divideService.query(
                str(body, "orderId"), str(body, "divideRequestId"));
        if (result == null) {
            return Map.of("status", "", "message", "", "supported", false);
        }
        return Map.of("status", nvl(result.status()), "message", nvl(result.message()), "supported", true);
    }

    /** 自助结算（提现出款）。 */
    @PostMapping("/settle")
    public Map<String, Object> settle(@RequestBody Map<String, Object> body) {
        YeepaySettleService.SettleResult result = settleService.selfSettle(
                str(body, "merchantNo"), str(body, "settleRequestNo"));
        return Map.of(
                "accepted", result.accepted(),
                "yeepayFlowNo", nvl(result.yeepayFlowNo()),
                "status", nvl(result.status()),
                "failReason", nvl(result.failReason()));
    }

    /** 查询结算结果（提现补偿用）。 */
    @PostMapping("/settle/query")
    public Map<String, Object> settleQuery(@RequestBody Map<String, Object> body) {
        YeepaySettleService.SettleQueryResult result = settleService.queryRecords(
                str(body, "merchantNo"), str(body, "settleRequestNo"));
        if (result == null) {
            return Map.of("status", "", "message", "", "supported", false);
        }
        return Map.of("status", nvl(result.status()), "message", nvl(result.message()), "supported", true);
    }

    /** 查询待结算金额（对账用）。 */
    @PostMapping("/settle/balance")
    public Map<String, Object> settleBalance(@RequestBody Map<String, Object> body) {
        long amountFen = settleService.querySettlableAmountFen(str(body, "merchantNo"));
        return Map.of("settlableAmountFen", amountFen);
    }

    /** 平台商商编（server 侧组装分账报文时需要）。 */
    @PostMapping("/config")
    public Map<String, Object> config() {
        return Map.of(
                "platformMerchantNo", nvl(properties.getPlatformMerchantNo()),
                "merchantNo", nvl(properties.getMerchantNo()),
                "divideIntervalMs", properties.getDivideIntervalMs());
    }

    private String str(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 解析分账的「收款商户」编号。
     *
     * <p><b>为什么由本服务决定而不信任调用方</b>：易宝 {@code divide/apply} 的
     * {@code merchantNo} 语义是<b>收款商户（平台商）</b>，
     * {@code divideDetail[].ledgerNo} 才是分账接收方（经营者子商户）。
     *
     * <p>历史上 server 侧曾把「第一个经营者的子商户号」当成 merchantNo 传过来，
     * 这会让易宝按「你从自己的子商户账户给另一个子商户分账」处理而失败。
     * 平台商编号是本服务的配置项，由本服务兜底比让调用方猜更可靠。
     */
    private String resolveMerchantNo(Map<String, Object> body) {
        String fromBody = str(body, "merchantNo");
        if (fromBody != null && !fromBody.isBlank()) {
            return fromBody;
        }
        String platform = properties.getMerchantNo();
        if (platform == null || platform.isBlank()) {
            throw new IllegalStateException(
                    "易宝服务商商编未配置（app.pay.yeepay.merchant-no），无法发起分账");
        }
        return platform;
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }
}
