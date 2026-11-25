package com.wuling.finance.payee;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link YeepaySettleCardPort} 的远程实现：转发到 trade-service
 * {@code /internal/yeepay/settle-card}。
 *
 * <p><b>未启用易宝时直接拒绝</b>：返回 {@code accepted=false} 并说明原因，
 * 而不是静默成功——静默成功会让经营者以为卡已绑好，提现时才发现。
 */
@Component
public class RemoteYeepaySettleCardAdapter implements YeepaySettleCardPort {

    private static final Logger log = LoggerFactory.getLogger(RemoteYeepaySettleCardAdapter.class);

    private final RestClient internalRestClient;
    private final boolean yeepayEnabled;

    public RemoteYeepaySettleCardAdapter(@Qualifier("internalRestClient") RestClient internalRestClient,
                                         @Value("${app.pay.yeepay.channel:}") String yeepayChannel) {
        this.internalRestClient = internalRestClient;
        this.yeepayEnabled = "yeepay".equalsIgnoreCase(
                yeepayChannel == null ? "" : yeepayChannel.trim());
    }

    @Override
    @SuppressWarnings("unchecked")
    public BindResult bind(String merchantNo, String accountType, String cardNo,
                           String bankCode, String branchCode) {
        if (!yeepayEnabled) {
            return new BindResult(false, null,
                    "易宝通道未启用（app.pay.yeepay.channel 未配置为 yeepay）");
        }
        if (merchantNo == null || merchantNo.isBlank()) {
            return new BindResult(false, null, "尚未完成商户入驻，无法绑定结算卡");
        }
        Map<String, Object> body = new HashMap<>();
        body.put("merchantNo", merchantNo);
        // 易宝结算卡类型：DEBIT_CARD 借记卡 / ENTERPRISE_ACCOUNT 对公账户
        body.put("bankCardType", "PUBLIC".equalsIgnoreCase(accountType)
                ? "ENTERPRISE_ACCOUNT" : "DEBIT_CARD");
        body.put("accountNo", cardNo);
        if (bankCode != null && !bankCode.isBlank()) {
            body.put("bankCode", bankCode);
        }
        if (branchCode != null && !branchCode.isBlank()) {
            body.put("branchCode", branchCode);
        }
        try {
            Map<String, Object> response = internalRestClient.post()
                    .uri("/internal/yeepay/settle-card")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (response == null) {
                return new BindResult(false, null, "结算卡服务无响应");
            }
            boolean accepted = Boolean.TRUE.equals(response.get("accepted"));
            String settleCardId = response.get("settleCardId") == null
                    ? null : String.valueOf(response.get("settleCardId"));
            String failReason = response.get("failReason") == null
                    ? null : String.valueOf(response.get("failReason"));
            return new BindResult(accepted, settleCardId, failReason);
        } catch (Exception e) {
            log.error("结算卡绑定调用失败 merchantNo={} err={}", merchantNo, e.getMessage());
            return new BindResult(false, null, "结算卡绑定请求失败，请稍后重试");
        }
    }
}
