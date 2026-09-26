package com.wuling.marketing.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户券展示视图：保留持券字段，并补齐模板名称、金额、门槛与有效期信息。
 */
@Data
public class UserCouponView {

    private Long id;
    private Long userId;
    private Long couponId;
    private String couponCode;
    private String name;
    private String type;
    private Long amount;
    private Long threshold;
    private String brand;
    private String scenes;
    private String source;
    private String description;
    private String image;
    private String validityType;
    private LocalDateTime validityStart;
    private LocalDateTime validityEnd;
    private Integer validityDays;
    private LocalDateTime receiveTime;
    private LocalDateTime expireAt;
    private Boolean usable;
    private String status;
    private Long lockOrderId;
    private LocalDateTime useTime;
}