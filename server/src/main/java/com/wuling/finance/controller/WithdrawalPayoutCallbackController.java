package com.wuling.finance.controller;

import com.wuling.finance.service.WithdrawalService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 提现出款结果内部回调（供 trade-service 转发微信转账结果）。
 *
 * <p><b>为什么是内部接口</b>：微信转账回调打给 trade-service（承载 SDK 与验签），
 * trade 验签解密后，把「某提现单成功/失败」通过本接口回传 server，
 * server 据此收敛提现状态（confirmPaid / confirmFailed）。
 *
 * <p><b>安全约束</b>：{@code /internal/**} 不在网关路由范围，且仅监听 127.0.0.1，
 * 不经公网暴露。本接口只接受 trade 服务端转发，不接受前端。
 */
@RestController
@RequestMapping("/internal/withdrawals")
public class WithdrawalPayoutCallbackController {

    private final WithdrawalService withdrawalService;

    public WithdrawalPayoutCallbackController(WithdrawalService withdrawalService) {
        this.withdrawalService = withdrawalService;
    }

    /**
     * 接收转账结果。
     *
     * @param withdrawNo 提现单号
     * @param body       { success: boolean, failReason: string }
     */
    @PostMapping("/{withdrawNo}/payout-result")
    public Map<String, Object> payoutResult(@PathVariable String withdrawNo,
                                            @RequestBody Map<String, Object> body) {
        boolean success = Boolean.TRUE.equals(body.get("success"));
        String failReason = body.get("failReason") == null ? null : String.valueOf(body.get("failReason"));
        if (success) {
            withdrawalService.confirmPaid(withdrawNo);
        } else {
            withdrawalService.confirmFailed(withdrawNo,
                    failReason == null || failReason.isBlank() ? "微信转账失败" : failReason);
        }
        return Map.of("ok", true);
    }
}
