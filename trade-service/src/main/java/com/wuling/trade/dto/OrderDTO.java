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
    private Long totalAmount;
    private Long originalAmount;
    private Long discountAmount;
    private Long paidAmount;
    private String refundStatus;
    private String createTime;
    private String payTime;
    private String verifyTime;
    private String completeTime;
    private List<Item> items;

    @Data
    public static class Item {
        private String productId;
        private String name;
        private String spec;
        private Long unitPrice;
        private Long originalPrice;
        private Integer quantity;
        private Long subTotal;
    }
}
