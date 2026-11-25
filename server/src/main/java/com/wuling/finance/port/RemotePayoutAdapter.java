package com.wuling.finance.port;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link PayoutPort} 的远程实现。
 *
 * <p>调用 trade-service 的 {@code /internal/payout/apply} 与 {@code /query}。
 * 微信通道由 trade-service 按 userId 查 openid 发起转账；
 * 易宝通道由本适配器把 server 侧查得的子商户号与结算卡 ID 一并下发。
 *
 * <p><b>fail-closed</b>：任何异常（网络抖动 / 下游不可用 / 响应异常）
 * 一律返回 accepted=false，由调用方解冻，绝不静默成功。
 */
@Component
public class RemotePayoutAdapter implements PayoutPort {

    private static final Logger log = LoggerFactory.getLogger(RemotePayoutAdapter.class);

    private final RestClient internalRestClient;

    public RemotePayoutAdapter(RestClient internalRestClient) {
        this.internalRestClient = internalRestClient;
    }

    @Override
    @SuppressWarnings("unchecked")
    public PayoutResult transfer(String withdrawNo, long amountFen, Payee payee, String remark) {
        if (payee == null) {
            return new PayoutResult(false, false, null, "收款方信息缺失");
        }
        Map<String, Object> body = new HashMap<>();
        body.put("withdrawNo", withdrawNo);
        body.put("amountFen", amountFen);
        body.put("remark", remark == null ? "五零时光提现" : remark);
        body.put("userId", payee.userId());
        body.put("subjectId", payee.subjectId());
        if (payee.yeepayMerchantNo() != null) {
            body.put("yeepayMerchantNo", payee.yeepayMerchantNo());
        }
        if (payee.settleCardId() != null) {
            body.put("settleCardId", payee.settleCardId());
        }

        try {
            Map<String, Object> response = internalRestClient.post()
                    .uri("/internal/payout/apply")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (response == null) {
                return new PayoutResult(false, false, null, "出款服务无响应");
            }
            boolean accepted = Boolean.TRUE.equals(response.get("accepted"));
            boolean immediatePaid = Boolean.TRUE.equals(response.get("immediatePaid"));
            String batchNo = response.get("batchNo") == null ? null : String.valueOf(response.get("batchNo"));
            String failReason = response.get("failReason") == null ? null : String.valueOf(response.get("failReason"));
            return new PayoutResult(accepted, immediatePaid, batchNo, failReason);
        } catch (Exception e) {
            log.error("提现出款调用失败 withdrawNo={} channel={} err={}",
                    withdrawNo, payee.channel(), e.getMessage());
            return new PayoutResult(false, false, null, "出款服务调用失败：" + e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public PayoutQueryResult query(String withdrawNo, String yeepayMerchantNo) {
        Map<String, Object> body = new HashMap<>();
        body.put("withdrawNo", withdrawNo);
        if (yeepayMerchantNo != null && !yeepayMerchantNo.isBlank()) {
            body.put("yeepayMerchantNo", yeepayMerchantNo);
        }
        try {
            Map<String, Object> response = internalRestClient.post()
                    .uri("/internal/payout/query")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (response == null) {
                return null;
            }
            boolean supported = Boolean.TRUE.equals(response.get("supported"));
            if (!supported) {
                return new PayoutQueryResult(false, null, null);
            }
            String status = response.get("status") == null ? null : String.valueOf(response.get("status"));
            String failReason = response.get("failReason") == null ? null : String.valueOf(response.get("failReason"));
            return new PayoutQueryResult(true, status, failReason);
        } catch (Exception e) {
            // 查不到是补偿流程的正常分支，不视为故障
            log.warn("提现出款查询失败 withdrawNo={} err={}", withdrawNo, e.getMessage());
            return null;
        }
    }
}
