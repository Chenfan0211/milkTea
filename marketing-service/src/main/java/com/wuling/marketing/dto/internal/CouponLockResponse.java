package com.wuling.marketing.dto.internal;

import com.fasterxml.jackson.annotation.JsonInclude;

/** trade -> marketing 锁券响应。成功返回金额，业务失败返回 message，HTTP 均为 200。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CouponLockResponse(boolean success,
                                 Long userCouponId,
                                 Long couponId,
                                 Long discountAmount,
                                 String message) {

    public static CouponLockResponse success(Long userCouponId, Long couponId, long discountAmount) {
        return new CouponLockResponse(true, userCouponId, couponId, discountAmount, null);
    }

    public static CouponLockResponse failure(String message) {
        return new CouponLockResponse(false, null, null, null, message);
    }
}
