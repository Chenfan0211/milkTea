package com.wuling.trade.port;

import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** {@link CouponPort} 的营销服务远程实现。 */
@Component
public class RemoteCouponAdapter implements CouponPort {

    private static final Logger log = LoggerFactory.getLogger(RemoteCouponAdapter.class);

    private final RestClient restClient;

    public RemoteCouponAdapter(@Qualifier("marketingInternalRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    @SuppressWarnings("unchecked")
    public LockResult lock(Long userId,
                           Long userCouponId,
                           String orderNo,
                           Long storeSubjectId,
                           List<Long> productIds,
                           List<ItemAmount> items,
                           String scene,
                           long orderAmount) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", userId);
        payload.put("userCouponId", userCouponId);
        payload.put("orderNo", orderNo);
        payload.put("storeSubjectId", storeSubjectId);
        payload.put("productIds", productIds);
        if (items != null) {
            payload.put("items", items.stream()
                    .filter(item -> item != null)
                    .map(item -> {
                        Map<String, Object> value = new HashMap<>();
                        value.put("productId", item.productId());
                        value.put("amount", item.amount());
                        return value;
                    })
                    .toList());
        }
        payload.put("scene", scene);
        payload.put("orderAmount", orderAmount);
        try {
            Map<String, Object> body = restClient.post()
                    .uri("/internal/coupons/lock")
                    .body(payload)
                    .retrieve()
                    .body(Map.class);
            return parseLockResult(body);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("锁券失败 userId={} userCouponId={} orderNo={} err={}",
                    userId, userCouponId, orderNo, e.getMessage());
            // 券服务不可用对用户而言是「本次优惠不可用」，属业务失败：
            // 用 400 让前端展示可读原因，避免污染 5xx（500 应留给真正的系统故障）。
            throw new BusinessException(ResultCode.BAD_REQUEST, "优惠券服务暂不可用，请稍后重试");
        }
    }

    @Override
    public void releaseAfterLockFailure(String orderNo) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderNo", orderNo);
        payload.put("reason", "下单事务失败");
        try {
            Map<String, Object> body = restClient.post()
                    .uri("/internal/coupons/release")
                    .body(payload)
                    .retrieve()
                    .body(Map.class);
            if (body == null || !Boolean.TRUE.equals(body.get("success"))) {
                throw new IllegalStateException("营销服务未确认释放");
            }
        } catch (Exception e) {
            // 此处无法让已回滚的下单事务恢复；保留错误日志供补偿巡检。
            log.error("下单失败后释放优惠券失败，需人工补偿 orderNo={} err={}", orderNo, e.getMessage());
        }
    }

    /**
     * 解析营销侧锁券响应。
     *
     * <p><b>为什么单独抽方法</b>：`discountAmount` 在 LockResult 里是原始 `long`，
     * 若响应缺失该字段，直接拆箱会抛 NPE（表现为 500）。这里显式判空并转成
     * 业务异常（400），让前端能展示可读原因而不是「服务器内部错误」。
     *
     * @param body 营销侧返回体（可能为 null）
     * @return 锁券结果
     */
    static LockResult parseLockResult(Map<String, Object> body) {
        if (body == null || !Boolean.TRUE.equals(body.get("success"))) {
            String message = body == null ? null : asString(body.get("message"));
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    message == null ? "优惠券不可用" : message);
        }
        Long discount = asLong(body.get("discountAmount"));
        if (discount == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "优惠券抵扣结果缺失");
        }
        return new LockResult(asLong(body.get("userCouponId")), asLong(body.get("couponId")), discount);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long asLong(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return Long.valueOf(String.valueOf(value));
    }
}
