package com.wuling.trade.dto;

import lombok.Data;

import java.util.List;

@Data
public class OrderDTO {

    private Long id;
    private String orderNo;
    private Long userId;
    private String user;
    private Long storeSubjectId;
    private String store;
    private String summary;
    private String mealType;
    private String status;
    private String payStatus;
    private String pickupCode;
    /** 订单来源分类：store / stored-value / gift-card，供小程序订单页页签过滤 */
    private String category;
    private Long totalAmount;
    private Long originalAmount;
    private Long discountAmount;
    private Long paidAmount;
    /** 优惠券抵扣金额（分） */
    private Long couponDiscount;
    private String refundStatus;
    private String createTime;
    private String payTime;
    private String verifyTime;
    private String completeTime;
    private List<Item> items;

    @Data
    public static class Item {
        /** 订单条目主键，供前端列表 wx:key 使用 */
        private Long id;
        private String productId;
        private String name;
        private String spec;
        /** 商品图（供小程序订单详情页展示，缺失时前端回落默认图） */
        private String image;
        private Long unitPrice;
        private Long originalPrice;
        private Integer quantity;
        private Long subTotal;
    }
}