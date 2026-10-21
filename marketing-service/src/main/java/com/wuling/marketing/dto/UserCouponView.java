package com.wuling.marketing.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

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
    /** 领取后延迟生效天数；NULL/0 表示立即生效。前端据此展示「N 天后生效」。 */
    private Integer effectiveDelayDays;
    private LocalDateTime receiveTime;
    private LocalDateTime expireAt;
    /**
     * 此刻能不能拿去下单 / 核销。<b>不是「有没有过期」</b> —— 已使用、订单占用中、已过期都是 false。
     */
    private Boolean usable;
    /**
     * 该券当前的归类是不是「已过期」（库表 EXPIRED，或仍是 UNUSED 但已过有效期）。
     *
     * <p>由服务端统一判定：前端不得再用 {@code usable == false} 推断过期 —— 已使用券同样是 usable=false。
     */
    private Boolean expired;
    private String status;
    private Long lockOrderId;
    private LocalDateTime useTime;

    /**
     * 适用门店（数字 subjectId）；空数组表示不限制。
     * 前端按此过滤「券适用门店」并在下单前做门店校验。
     */
    private List<Long> applicableStoreIds;

    /** 适用商品（数字 productId）；空数组表示不限制。 */
    private List<Long> applicableProductIds;
}