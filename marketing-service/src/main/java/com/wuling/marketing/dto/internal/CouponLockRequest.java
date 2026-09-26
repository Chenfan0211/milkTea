package com.wuling.marketing.dto.internal;

import lombok.Data;

import java.util.List;

/** trade -> marketing 锁券请求。 */
@Data
public class CouponLockRequest {
    private Long userId;
    private Long userCouponId;
    private String orderNo;
    private Long storeSubjectId;
    private List<Long> productIds;
    private String scene;
    private Long orderAmount;
}
