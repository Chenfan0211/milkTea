package com.wuling.trade.port;

import com.wuling.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * {@link SettlementQueryPort} 的远程实现（第 7 期）。
 *
 * <p>调用 server 的 {@code /internal/settlement-status} 只读接口。
 *
 * <p><b>失败语义（与 ProductQueryPort 不同）</b>：
 * 资金相关的查询失败**必须抛异常**，不能静默返回「未结算」——
 * 否则下游故障会被误判为「可以退款」，导致资金穿透。
 * 宁可让退款请求失败（用户重试），也不能放过已结算订单的退款。
 */
@Component
public class RemoteSettlementQueryAdapter implements SettlementQueryPort {

    private static final Logger log = LoggerFactory.getLogger(RemoteSettlementQueryAdapter.class);

    private final RestClient restClient;

    public RemoteSettlementQueryAdapter(
            @Qualifier("serverInternalRestClient") RestClient serverInternalRestClient) {
        this.restClient = serverInternalRestClient;
    }

    @Override
    @SuppressWarnings("unchecked")
    public SettlementStatus query(Long orderId) {
        Map<String, Object> body;
        try {
            body = restClient.get()
                    .uri("/internal/settlement-status?orderId={id}", orderId)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            log.error("查询结算状态失败 orderId={} err={}", orderId, e.getMessage());
            // 保守失败：宁可拒绝退款，也不能误判为"未结算"
            throw new BusinessException(com.wuling.common.api.ResultCode.ERROR,
                    "结算状态查询失败，请稍后重试");
        }
        if (body == null) {
            throw new BusinessException(com.wuling.common.api.ResultCode.ERROR,
                    "结算状态查询返回为空，请稍后重试");
        }
        SettlementStatus status = new SettlementStatus();
        status.setSettled(Boolean.TRUE.equals(body.get("settled")));
        status.setHasSettlement(Boolean.TRUE.equals(body.get("hasSettlement")));
        return status;
    }
}
