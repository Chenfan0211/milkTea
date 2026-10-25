package com.wuling.marketing.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 优惠券到期扫描候选行。 */
@Data
public class CouponExpiryCandidate {
    private Long id;
    private Long userId;
    private Long couponId;
    private String couponName;
    private Long amount;
    private LocalDateTime expireAt;
}
