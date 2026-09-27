package com.wuling.trade.port;

import com.wuling.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 锁券响应解析回归。
 *
 * <p>背景（潜在 500）：营销侧返回的 discountAmount 若缺失/为 null，
 * 旧实现直接用它构造 {@code LockResult(..., long discountAmount)}，
 * Long 自动拆箱抛 NPE，被上层包装后表现为下单失败。
 */
class RemoteCouponAdapterParseTest {

    @Test
    @DisplayName("discountAmount 为 null 时必须抛业务异常，不得 NPE")
    void nullDiscountAmountThrowsBusinessException() {
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("userCouponId", 9);
        body.put("couponId", 3);
        body.put("discountAmount", null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> RemoteCouponAdapter.parseLockResult(body));
        assertEquals(400, ex.getCode(), "抵扣结果缺失应按业务错误返回 400，而不是 500");
    }

    @Test
    @DisplayName("discountAmount 缺失（字段不存在）同样按业务异常处理")
    void missingDiscountAmountThrowsBusinessException() {
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("userCouponId", 9);
        body.put("couponId", 3);

        assertThrows(BusinessException.class, () -> RemoteCouponAdapter.parseLockResult(body));
    }

    @Test
    @DisplayName("正常响应解析出抵扣金额")
    void parsesDiscountAmount() {
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("userCouponId", 9);
        body.put("couponId", 3);
        body.put("discountAmount", 300);

        CouponPort.LockResult result = RemoteCouponAdapter.parseLockResult(body);
        assertEquals(9L, result.userCouponId());
        assertEquals(3L, result.couponId());
        assertEquals(300L, result.discountAmount());
    }

    @Test
    @DisplayName("success=false 时按业务异常抛出并带上后端消息")
    void failureBodyThrows() {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", "优惠券已锁定");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> RemoteCouponAdapter.parseLockResult(body));
        assertEquals(400, ex.getCode());
        assertEquals("优惠券已锁定", ex.getMessage());
    }
}
