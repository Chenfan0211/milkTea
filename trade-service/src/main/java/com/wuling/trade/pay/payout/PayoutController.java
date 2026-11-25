package com.wuling.trade.pay.payout;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 出款内部接口（供 server 的提现闭环调用）。
 *
 * <p><b>为什么是内部接口</b>：提现的出款动作应由 finance（server）在
 * 「审核通过」后触发，但支付/出款 SDK 在 trade-service，故由 server 经内部
 * RestClient 调本接口发起出款，server 再根据结果收敛提现状态。
 *
 * <p><b>安全约束</b>：{@code /internal/**} 不在网关路由范围，且各服务仅监听
 * 127.0.0.1，不会经公网暴露。接口只做「发起出款受理」，不落库、不改状态。
 *
 * <p><b>收款方来源</b>：
 * <ul>
 *   <li>微信通道：{@code openid} 由本服务按 userId 查得，绝不接受前端传入；</li>
 *   <li>易宝通道：{@code yeepayMerchantNo} / {@code settleCardId} 由 server
 *       按 subjectId 查库后下发（server 是这些数据的唯一属主）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal/payout")
public class PayoutController {

    private final PayoutService payoutService;

    public PayoutController(PayoutService payoutService) {
        this.payoutService = payoutService;
    }

    /**
     * 发起提现出款（受理）。
     *
     * <p>请求体字段（按通道取用，其余忽略）：
     * {@code withdrawNo}、{@code amountFen}、{@code userId}、{@code remark}、
     * {@code subjectId}、{@code yeepayMerchantNo}、{@code settleCardId}。
     *
     * @return { accepted, immediatePaid, batchNo, failReason }
     */
    @PostMapping("/apply")
    public Map<String, Object> apply(@RequestBody Map<String, Object> body) {
        String withdrawNo = String.valueOf(body.get("withdrawNo"));
        long amountFen = ((Number) body.get("amountFen")).longValue();
        String remark = body.get("remark") == null ? null : String.valueOf(body.get("remark"));

        PayoutGateway.PayoutResult result;
        if (hasYeepayPayee(body)) {
            // 易宝通道：收款方由 server 下发（子商户号 + 结算卡）
            Long subjectId = asLong(body.get("subjectId"));
            String merchantNo = String.valueOf(body.get("yeepayMerchantNo"));
            String settleCardId = String.valueOf(body.get("settleCardId"));
            result = payoutService.apply(withdrawNo, amountFen,
                    PayoutGateway.PayeeRef.ofYeepay(subjectId, merchantNo, settleCardId), remark);
        } else {
            // 微信通道：openid 由本服务按 userId 查得
            Long userId = asLong(body.get("userId"));
            result = payoutService.applyWx(withdrawNo, amountFen, userId, remark);
        }

        return Map.of(
                "accepted", result.accepted(),
                "immediatePaid", payoutService.isImmediateChannel() && result.accepted(),
                "batchNo", result.batchNo() == null ? "" : result.batchNo(),
                "failReason", result.failReason() == null ? "" : result.failReason());
    }

    /**
     * 主动查询出款结果（供 server 的提现补偿任务调用）。
     *
     * <p>易宝需要 {@code yeepayMerchantNo} 才能查结算记录；微信通道忽略该字段。
     *
     * @return { status, failReason, supported }，通道不支持时 status 为空串
     */
    @PostMapping("/query")
    public Map<String, Object> query(@RequestBody Map<String, Object> body) {
        String withdrawNo = String.valueOf(body.get("withdrawNo"));
        String merchantNo = body.get("yeepayMerchantNo") == null
                ? null : String.valueOf(body.get("yeepayMerchantNo"));
        PayoutGateway.PayoutQueryResult result = payoutService.query(withdrawNo, merchantNo);
        if (result == null) {
            return Map.of("status", "", "failReason", "", "supported", false);
        }
        return Map.of(
                "status", result.status() == null ? "" : result.status(),
                "failReason", result.failReason() == null ? "" : result.failReason(),
                "supported", true);
    }

    private boolean hasYeepayPayee(Map<String, Object> body) {
        return body.get("yeepayMerchantNo") != null && body.get("settleCardId") != null;
    }

    private Long asLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
