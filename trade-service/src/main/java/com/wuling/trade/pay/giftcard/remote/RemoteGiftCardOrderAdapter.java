package com.wuling.trade.pay.giftcard.remote;

import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.trade.pay.giftcard.GiftCardOrderPort;
import com.wuling.trade.pay.giftcard.GiftCardRefundResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

/**
 * Remote adapter for gift-card order state transitions owned by marketing-service.
 *
 * <p>Payment and refund operations are money-critical, so transport or remote
 * failures are surfaced as {@link BusinessException} rather than hidden as a
 * missing order.
 */
@Component
public class RemoteGiftCardOrderAdapter implements GiftCardOrderPort {

    private static final Logger log = LoggerFactory.getLogger(RemoteGiftCardOrderAdapter.class);
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final RestClient restClient;
    private final String internalServiceToken;

    public RemoteGiftCardOrderAdapter(
            @Qualifier("marketingInternalRestClient") RestClient marketingInternalRestClient,
            @Value("${app.internal.service-token:}") String internalServiceToken) {
        this.restClient = marketingInternalRestClient;
        this.internalServiceToken = internalServiceToken == null ? "" : internalServiceToken;
    }

    @Override
    @SuppressWarnings("unchecked")
    public GiftCardOrderView findByOrderNo(String orderNo) {
        try {
            Map<String, Object> body = restClient.get()
                    .uri("/internal/gift-card-orders/{orderNo}", orderNo)
                    .header(INTERNAL_TOKEN_HEADER, internalServiceToken)
                    .retrieve()
                    .body(Map.class);
            if (body == null || !Boolean.TRUE.equals(body.get("found"))) {
                return null;
            }
            GiftCardOrderView view = new GiftCardOrderView();
            view.setOrderNo(asString(body.get("orderNo")));
            view.setUserId(asLong(body.get("userId")));
            view.setAmount(asLong(body.get("amount")));
            view.setPayStatus(asString(body.get("payStatus")));
            view.setStatus(asString(body.get("status")));
            view.setVerifyStatus(asString(body.get("verifyStatus")));
            view.setRefundStatus(asString(body.get("refundStatus")));
            view.setTransactionId(asString(body.get("transactionId")));
            view.setExpireTime(asLocalDateTime(body.get("expireTime")));
            return view;
        } catch (Exception e) {
            log.error("query gift card order failed orderNo={} err={}", orderNo, e.getMessage());
            throw new BusinessException(ResultCode.ERROR, "礼品卡订单查询失败，请稍后重试");
        }
    }

    @Override
    public void settle(String orderNo, String transactionId, String payerOpenid,
                       Long callbackAmount) {
        if (callbackAmount == null || callbackAmount <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "礼品卡支付回调金额不能为空或小于等于 0");
        }
        if (!StringUtils.hasText(transactionId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "礼品卡支付回调交易号不能为空");
        }
        Map<String, Object> body = new HashMap<>();
        body.put("transactionId", transactionId.trim());
        body.put("payerOpenid", valueOrEmpty(payerOpenid));
        body.put("callbackAmount", callbackAmount);
        post("/internal/gift-card-orders/{orderNo}/settle", orderNo, body,
                "礼品卡订单入账失败，请稍后重试");
    }

    @Override
    public GiftCardRefundResult refundBegin(String orderNo, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("reason", reason);
        Map<String, Object> result = postForBody(
                "/internal/gift-card-orders/{orderNo}/refund-begin", orderNo, body,
                "礼品卡退款受理失败，请稍后重试");
        return new GiftCardRefundResult(
                asString(result.get("refundNo")),
                asLong(result.get("amount")),
                asString(result.get("status")));
    }

    @Override
    public void refundConfirm(String orderNo, String refundNo, String wxRefundId) {
        Map<String, Object> body = new HashMap<>();
        body.put("refundNo", refundNo);
        body.put("wxRefundId", wxRefundId);
        post("/internal/gift-card-orders/{orderNo}/refund-confirm", orderNo, body,
                "礼品卡退款确认失败，请稍后重试");
    }

    @Override
    public void refundFail(String orderNo, String refundNo, String failReason) {
        Map<String, Object> body = new HashMap<>();
        body.put("refundNo", refundNo);
        body.put("failReason", failReason);
        post("/internal/gift-card-orders/{orderNo}/refund-fail", orderNo, body,
                "礼品卡退款失败状态同步失败，请稍后重试");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postForBody(String uri, String orderNo,
                                            Map<String, Object> body, String errorMessage) {
        try {
            Map<String, Object> result = restClient.post()
                    .uri(uri, orderNo)
                    .header(INTERNAL_TOKEN_HEADER, internalServiceToken)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            return result == null ? Map.of() : result;
        } catch (Exception e) {
            log.error("call gift card internal endpoint failed uri={} orderNo={} err={}",
                    uri, orderNo, e.getMessage());
            throw new BusinessException(ResultCode.ERROR, errorMessage);
        }
    }

    private void post(String uri, String orderNo, Map<String, Object> body, String errorMessage) {
        postForBody(uri, orderNo, body, errorMessage);
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number number
                ? number.longValue()
                : Long.valueOf(String.valueOf(value));
    }

    private java.time.LocalDateTime asLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        return java.time.LocalDateTime.parse(String.valueOf(value));
    }
}
