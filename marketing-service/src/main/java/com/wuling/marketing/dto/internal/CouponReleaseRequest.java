package com.wuling.marketing.dto.internal;

import lombok.Data;

/** trade -> marketing 释放请求。 */
@Data
public class CouponReleaseRequest {
    private String orderNo;
    private String reason;
}
