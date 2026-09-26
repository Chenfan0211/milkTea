package com.wuling.marketing.internal;

import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.marketing.dto.internal.CouponLockRequest;
import com.wuling.marketing.dto.internal.CouponLockResponse;
import com.wuling.marketing.dto.internal.CouponOrderRequest;
import com.wuling.marketing.dto.internal.CouponReleaseRequest;
import com.wuling.marketing.dto.internal.CouponRestoreResponse;
import com.wuling.marketing.service.CouponService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CouponInternalControllerTest {

    private final CouponService couponService = mock(CouponService.class);
    private final CouponInternalController controller = new CouponInternalController(couponService);

    @Test
    @DisplayName("锁券成功按固定契约返回用户券、模板和抵扣额")
    void lockReturnsSuccessContract() {
        CouponLockRequest request = new CouponLockRequest();
        request.setUserId(9L);
        request.setUserCouponId(10L);
        request.setOrderNo("WX202609271234567890");
        request.setStoreSubjectId(101L);
        request.setProductIds(List.of(1L));
        request.setScene("dinein");
        request.setOrderAmount(1200L);
        when(couponService.lockByOrderNo(9L, 10L, "WX202609271234567890", 101L, List.of(1L), "dinein", 1200L))
                .thenReturn(new CouponService.CouponLockResult(10L, 20L, 300L));

        CouponLockResponse response = controller.lock(request);

        assertTrue(response.success());
        assertEquals(10L, response.userCouponId());
        assertEquals(20L, response.couponId());
        assertEquals(300L, response.discountAmount());
    }

    @Test
    @DisplayName("业务失败返回 HTTP 200 语义的 success=false 和 message")
    void lockBusinessFailureReturnsFailureBody() {
        CouponLockRequest request = new CouponLockRequest();
        when(couponService.lockByOrderNo(9L, 10L, "123", null, null, null, null))
                .thenThrow(new BusinessException(ResultCode.BAD_REQUEST, "订单金额未达到优惠券使用门槛"));
        request.setUserId(9L);
        request.setUserCouponId(10L);
        request.setOrderNo("123");

        CouponLockResponse response = controller.lock(request);

        assertFalse(response.success());
        assertEquals("订单金额未达到优惠券使用门槛", response.message());
    }

    @Test
    @DisplayName("consume、release、restore-refund 成功均返回 success=true")
    void otherOperationsReturnSuccessContract() {
        CouponOrderRequest consumeRequest = new CouponOrderRequest();
        consumeRequest.setUserId(9L);
        consumeRequest.setUserCouponId(10L);
        consumeRequest.setOrderNo("123");
        CouponReleaseRequest releaseRequest = new CouponReleaseRequest();
        releaseRequest.setOrderNo("123");
        releaseRequest.setReason("取消");
        when(couponService.restoreAfterRefund(9L, 10L, "123")).thenReturn(CouponService.UNUSED);

        Map<String, Object> consume = controller.consume(consumeRequest);
        Map<String, Object> release = controller.release(releaseRequest);
        CouponRestoreResponse restore = controller.restoreRefund(consumeRequest);

        assertTrue((Boolean) consume.get("success"));
        assertTrue((Boolean) release.get("success"));
        assertTrue(restore.success());
        assertEquals(CouponService.UNUSED, restore.status());
    }

    @Test
    @DisplayName("系统异常必须继续抛出，由框架返回 5xx")
    void systemExceptionPropagates() {
        CouponReleaseRequest request = new CouponReleaseRequest();
        request.setOrderNo("123");
        when(couponService.release(any(), any())).thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class, () -> controller.release(request));
    }
}