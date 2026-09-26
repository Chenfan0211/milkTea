package com.wuling.marketing.dto.internal;

import lombok.Data;

/** trade -> marketing 消费、退款恢复请求。 */
@Data
public class CouponOrderRequest {
    private Long userId;
    private Long userCouponId;
    private String orderNo;
}
