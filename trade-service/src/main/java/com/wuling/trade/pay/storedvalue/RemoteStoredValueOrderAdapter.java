package com.wuling.trade.pay.storedvalue;

import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * {@link StoredValueOrderPort} 的远程实现（第 15 期）。
 *
 * <p>调用 marketing-service 的 {@code /internal/stored-value-orders/**}。
 * {@code /internal/**} 不在网关路由范围内，且服务仅监听内网/本机，
 * 因此不会经公网暴露。
 *
 * <p><b>失败语义（与 {@code RemoteProductQueryAdapter} 不同）</b>：
 * 这里涉及资金，查询/入账失败一律抛异常，让微信按策略重试回调。
 * 若吞掉异常返回 null，回调会应答 SUCCESS，微信不再重试，
 * 用户付款但余额不到账 —— 属资损级问题。
 */
@Component
public class RemoteStoredValueOrderAdapter implements StoredValueOrderPort {

    private static final Logger log = LoggerFactory.getLogger(RemoteStoredValueOrderAdapter.class);

    private final RestClient restClient;

    public RemoteStoredValueOrderAdapter(
            @Qualifier("marketingInternalRestClient") RestClient marketingInternalRestClient) {
        this.restClient = marketingInternalRestClient;
    }

    @Override
    @SuppressWarnings("unchecked")
    public StoredValueOrderView findByOrderNo(String orderNo) {
        Map<String, Object> body;
        try {
            body = restClient.get()
                    .uri("/internal/stored-value-orders/{orderNo}", orderNo)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            log.error("查询储值订单失败 orderNo={} err={}", orderNo, e.getMessage());
            throw new BusinessException(ResultCode.ERROR, "储值订单查询失败，请稍后重试");
        }
        if (body == null || !Boolean.TRUE.equals(body.get("found"))) {
            return null;
        }
        StoredValueOrderView view = new StoredValueOrderView();
        view.setOrderNo(asString(body.get("orderNo")));
        view.setUserId(asLong(body.get("userId")));
        view.setAmount(asLong(body.get("amount")));
        view.setPayStatus(asString(body.get("payStatus")));
        return view;
    }

    @Override
    public void markPaid(String orderNo, String transactionId, String payerOpenid, Long callbackAmount) {
        try {
            restClient.post()
                    .uri("/internal/stored-value-orders/{orderNo}/settle", orderNo)
                    .body(Map.of(
                            "transactionId", transactionId == null ? "" : transactionId,
                            "payerOpenid", payerOpenid == null ? "" : payerOpenid,
                            "callbackAmount", callbackAmount == null ? 0L : callbackAmount
                    ))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.error("储值订单入账失败 orderNo={} err={}", orderNo, e.getMessage());
            // 抛出以让回调应答 FAIL，微信会重试；幂等由 marketing 侧条件更新保证
            throw new BusinessException(ResultCode.ERROR, "储值入账失败，请稍后重试");
        }
    }

    private String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private Long asLong(Object v) {
        return v == null ? null : Long.valueOf(String.valueOf(v));
    }
}
