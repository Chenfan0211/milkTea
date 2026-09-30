package com.wuling.trade.pay.payout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 提现出款结果转发器（trade → server 内部接口）。
 *
 * <p>微信转账回调打给 trade-service 验签解密后，通过本类把「某提现单成功/失败」
 * 转发给 server 的 {@code /internal/withdrawals/{withdrawNo}/payout-result}，
 * 由 server 收敛提现状态。
 *
 * <p><b>失败语义</b>：转发失败仅记日志（微信会按应答重试），
 * 不抛出 —— 重试由微信回调机制兜底，无需本层自行重试。
 */
@Component
public class PayoutResultForwarder {

    private static final Logger log = LoggerFactory.getLogger(PayoutResultForwarder.class);

    private final RestClient serverInternalRestClient;

    public PayoutResultForwarder(@Qualifier("serverInternalRestClient") RestClient serverInternalRestClient) {
        this.serverInternalRestClient = serverInternalRestClient;
    }

    /**
     * 转发转账结果给 server。
     *
     * @param withdrawNo 提现单号
     * @param success    是否成功
     * @param failReason 失败原因（success=false 时有效）
     */
    public void forward(String withdrawNo, boolean success, String failReason) {
        try {
            serverInternalRestClient.post()
                    .uri("/internal/withdrawals/{no}/payout-result", withdrawNo)
                    .body(Map.of(
                            "success", success,
                            "failReason", failReason == null ? "" : failReason))
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            // 只记日志：微信回调会按 FAIL 应答重试，无需本层重试
            log.error("转发提现出款结果失败 withdrawNo={} success={} err={}",
                    withdrawNo, success, e.getMessage());
        }
    }
}
