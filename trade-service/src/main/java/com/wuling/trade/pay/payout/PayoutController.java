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
 * 「审核通过」后触发，但微信转账 SDK 在 trade-service，故由 server 经内部
 * RestClient 调本接口发起转账，server 再根据结果收敛提现状态。
 *
 * <p><b>安全约束</b>：{@code /internal/**} 不在网关路由范围，且各服务仅监听
 * 127.0.0.1，不会经公网暴露。接口只做「发起转账受理」，不落库、不改状态。
 *
 * <p><b>参数</b>：withdrawNo / amountFen / userId 均由调用方（server）在服务端
 * 依据审核结果提供；<b>openid 由本服务按 userId 查得</b>，绝不接受前端传入。
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
     * @return { accepted, batchNo, failReason }
     */
    @PostMapping("/apply")
    public Map<String, Object> apply(@RequestBody Map<String, Object> body) {
        String withdrawNo = String.valueOf(body.get("withdrawNo"));
        long amountFen = ((Number) body.get("amountFen")).longValue();
        Long userId = body.get("userId") == null ? null : ((Number) body.get("userId")).longValue();
        String remark = body.get("remark") == null ? null : String.valueOf(body.get("remark"));

        PayoutGateway.PayoutResult result = payoutService.apply(withdrawNo, amountFen, userId, remark);
        return Map.of(
                "accepted", result.accepted(),
                "immediatePaid", payoutService.isImmediateChannel() && result.accepted(),
                "batchNo", result.batchNo() == null ? "" : result.batchNo(),
                "failReason", result.failReason() == null ? "" : result.failReason());
    }
}
