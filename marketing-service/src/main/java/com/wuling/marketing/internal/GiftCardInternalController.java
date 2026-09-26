package com.wuling.marketing.internal;

import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.marketing.entity.GiftCardOrder;
import com.wuling.marketing.entity.GiftCardRefund;
import com.wuling.marketing.service.GiftCardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/** 礼品卡订单内部接口，供 trade-service 的微信支付与退款回调驱动状态机。 */
@RestController
@RequestMapping("/internal/gift-card-orders")
public class GiftCardInternalController {

    private static final Logger log = LoggerFactory.getLogger(GiftCardInternalController.class);

    private final GiftCardService giftCardService;

    public GiftCardInternalController(GiftCardService giftCardService) {
        this.giftCardService = giftCardService;
    }

    @GetMapping("/{orderNo}")
    public Map<String, Object> findByOrderNo(@PathVariable String orderNo) {
        GiftCardOrder order;
        try {
            order = giftCardService.requireByOrderNo(orderNo);
        } catch (BusinessException e) {
            if (e.getCode() != ResultCode.NOT_FOUND) {
                throw e;
            }
            log.info("内部查询礼品卡订单不存在 orderNo={}", orderNo);
            return Map.of("found", false);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("found", true);
        body.put("orderNo", order.getOrderNo());
        body.put("userId", order.getUserId());
        body.put("denominationId", order.getDenominationId());
        body.put("amount", order.getAmount());
        body.put("payStatus", order.getPayStatus());
        body.put("status", order.getStatus());
        body.put("verifyStatus", order.getVerifyStatus());
        body.put("refundStatus", order.getRefundStatus());
        body.put("transactionId", order.getTransactionId());
        body.put("expireTime", order.getExpireTime());
        return body;
    }

    @PostMapping("/{orderNo}/settle")
    public Map<String, Object> settle(@PathVariable String orderNo,
                                      @RequestBody Map<String, Object> body) {
        GiftCardOrder order = giftCardService.settle(
                orderNo,
                asString(body.get("transactionId")),
                asString(body.get("payerOpenid")),
                asLong(body.get("callbackAmount")));
        log.info("内部驱动礼品卡入账完成 orderNo={} payStatus={}", orderNo, order.getPayStatus());
        return Map.of(
                "orderNo", order.getOrderNo(),
                "payStatus", order.getPayStatus(),
                "status", order.getStatus());
    }

    @PostMapping("/{orderNo}/refund-begin")
    public Map<String, Object> refundBegin(@PathVariable String orderNo,
                                           @RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null ? null : asString(body.get("reason"));
        GiftCardRefund refund = giftCardService.refundBegin(orderNo, reason);
        return refundBody(refund);
    }

    @PostMapping("/{orderNo}/refund-confirm")
    public Map<String, Object> refundConfirm(@PathVariable String orderNo,
                                             @RequestBody Map<String, Object> body) {
        GiftCardRefund refund = giftCardService.refundConfirm(
                orderNo,
                asString(body.get("refundNo")),
                asString(body.get("wxRefundId")));
        return refundBody(refund);
    }

    @PostMapping("/{orderNo}/refund-fail")
    public Map<String, Object> refundFail(@PathVariable String orderNo,
                                          @RequestBody Map<String, Object> body) {
        GiftCardRefund refund = giftCardService.refundFail(
                orderNo,
                asString(body.get("refundNo")),
                asString(body.get("failReason")));
        return refundBody(refund);
    }

    private Map<String, Object> refundBody(GiftCardRefund refund) {
        Map<String, Object> body = new HashMap<>();
        body.put("refundNo", refund.getRefundNo());
        body.put("orderNo", refund.getOrderNo());
        body.put("amount", refund.getAmount());
        body.put("status", refund.getStatus());
        body.put("wxRefundId", refund.getWxRefundId());
        body.put("failReason", refund.getFailReason());
        return body;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return value instanceof Number number
                    ? number.longValue()
                    : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
