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
    /** 旧调用兼容字段；新调用优先使用 items。 */
    private List<Long> productIds;
    private String scene;
    /** 旧调用兼容字段：仅适用商品行净额（分）；为空时使用 orderAmount。 */
    private Long applicableAmount;
    /** 商品行净额（分）；每个元素表示一个下单商品行。 */
    private List<CouponItemAmount> items;
    /** 旧调用兼容字段：整单金额。 */
    private Long orderAmount;

    /** 单个商品行净额，amount 单位为分；重复 productId 由服务端按行累加。 */
    @Data
    public static class CouponItemAmount {
        private Long productId;
        private Long amount;
    }
}
