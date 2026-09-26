package com.wuling.trade.pay.storedvalue;

import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link StoredValueBalancePort} 的远程实现。
 *
 * <p>调用 marketing-service 的 {@code /internal/stored-value-orders/balance/**}。
 *
 * <p><b>失败语义（关键）</b>：
 * <ul>
 *   <li>HTTP 层异常（网络不可达、5xx）→ 抛 {@link BusinessException}，
 *       让订单事务回滚 —— 不能把「服务故障」当成「余额不足」放过；</li>
 *   <li>业务层 {@code success=false}（余额不足）→ 正常返回，
 *       由订单侧给出可读提示。</li>
 * </ul>
 * 两者混同会导致：服务抖动时误报「余额不足」，或余额不足时静默放行订单。
 */
@Component
public class RemoteStoredValueBalanceAdapter implements StoredValueBalancePort {

    private static final Logger log = LoggerFactory.getLogger(RemoteStoredValueBalanceAdapter.class);

    private final RestClient restClient;

    public RemoteStoredValueBalanceAdapter(
            @Qualifier("marketingInternalRestClient") RestClient marketingInternalRestClient) {
        this.restClient = marketingInternalRestClient;
    }

    @Override
    @SuppressWarnings("unchecked")
    public DeductResult deduct(Long userId, long amount, String bizNo) {
        Map<String, Object> body = post("/internal/stored-value-orders/balance/pay", userId, amount, bizNo);
        if (body == null) {
            throw new BusinessException(ResultCode.ERROR, "储值余额扣款失败，请稍后重试");
        }
        if (Boolean.TRUE.equals(body.get("success"))) {
            return DeductResult.ok();
        }
        // 业务性失败（余额不足等）：把后端原因透传，便于前端提示
        return DeductResult.fail(asString(body.get("message")));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void refund(Long userId, long amount, String bizNo) {
        Map<String, Object> body = post("/internal/stored-value-orders/balance/refund", userId, amount, bizNo);
        if (body == null || !Boolean.TRUE.equals(body.get("success"))) {
            String reason = body == null ? "无响应" : asString(body.get("message"));
            log.error("储值余额退回失败 userId={} amount={} bizNo={} reason={}",
                    userId, amount, bizNo, reason);
            // 退款场景必须抛错：静默失败会让用户「钱没退回来却显示已退款」
            throw new BusinessException(ResultCode.ERROR,
                    "储值余额退回失败：" + (reason == null ? "请稍后重试" : reason));
        }
        log.info("储值余额退回成功 userId={} amount={} bizNo={}", userId, amount, bizNo);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String uri, Long userId, long amount, String bizNo) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", userId);
        payload.put("amount", amount);
        payload.put("bizNo", bizNo);
        try {
            return restClient.post()
                    .uri(uri)
                    .body(payload)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            log.error("调用储值余额接口失败 uri={} userId={} amount={} bizNo={} err={}",
                    uri, userId, amount, bizNo, e.getMessage());
            throw new BusinessException(ResultCode.ERROR, "储值余额服务暂不可用，请稍后重试");
        }
    }

    private String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}