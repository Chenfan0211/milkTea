package com.wuling.marketing.dto.internal;

import com.fasterxml.jackson.annotation.JsonInclude;

/** trade -> marketing 退款恢复响应。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CouponRestoreResponse(boolean success, String status, String message) {

    public static CouponRestoreResponse success(String status) {
        return new CouponRestoreResponse(true, status, null);
    }

    public static CouponRestoreResponse failure(String message) {
        return new CouponRestoreResponse(false, null, message);
    }
}