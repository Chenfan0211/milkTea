package com.wuling.trade.dto;

import lombok.Data;
import java.util.List;

/** 售后单视图（用户端/后台端共用）。 */
@Data
public class MallAfterSaleVO {
    private Long id;
    private String afterSaleNo;
    private Long orderId;
    private String orderNo;
    private Long userId;
    private String userNick;
    private String status;
    private String reason;
    private Long refundAmount;
    private List<Item> items;
    private String applyTime;
    private String reviewTime;
    private String reviewer;
    private String completeTime;
    private Long refundId;
    private String refundNo;
    private String refundStatus;
    private String refundFailReason;

    @Data
    public static class Item {
        private Long orderItemId;
        private Long skuId;
        private String skuCode;
        private String name;
        private String spec;
        private String image;
        private Integer quantity;
        private Long unitPrice;
        private Long distributionCommission;
        private Long lineAmount;
    }
}
