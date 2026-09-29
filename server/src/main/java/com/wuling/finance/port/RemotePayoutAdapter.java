package com.wuling.finance.port;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * {@link PayoutPort} 的远程实现。
 *
 * <p>调用 trade-service 的 {@code /internal/payout/apply}。
 * 该内部接口在服务端按 userId 查询 openid 并发起转账，返回受理结果。
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
    public PayoutResult transfer(String withdrawNo, long amountFen, Long userId, String remark) {
        try {
            Map<String, Object> body = internalRestClient.post()
                    .uri("/internal/payout/apply")
                    .body(Map.of(
                            "withdrawNo", withdrawNo,
                            "amountFen", amountFen,
                            "userId", userId == null ? null : userId,
                            "remark", remark == null ? "五零时光提现" : remark))
                    .retrieve()
                    .body(Map.class);
            if (body == null) {
                return new PayoutResult(false, false, null, "出款服务无响应");
            }
            boolean accepted = Boolean.TRUE.equals(body.get("accepted"));
            boolean immediatePaid = Boolean.TRUE.equals(body.get("immediatePaid"));
            String batchNo = body.get("batchNo") == null ? null : String.valueOf(body.get("batchNo"));
            String failReason = body.get("failReason") == null ? null : String.valueOf(body.get("failReason"));
            return new PayoutResult(accepted, immediatePaid, batchNo, failReason);
        } catch (Exception e) {
            log.error("提现出款调用失败 withdrawNo={} err={}", withdrawNo, e.getMessage());
            return new PayoutResult(false, false, null, "出款服务调用失败：" + e.getMessage());
        }
    }
}
