package com.wuling.trade.dto;

import lombok.Data;

import java.util.List;

/** 商城结算确认结果：金额以服务端为唯一事实来源。 */
@Data
public class MallCheckoutQuoteDTO {
    private List<Item> items;
    private Long payableAmount;
    private Boolean leaderBuyer;
    /** 是否可以按当前购物车直接下单（归属主体/分账规则必须一致）。 */
    private Boolean orderable;
    /** 不可下单原因，可下单时为 null。 */
    private String orderError;

    @Data
    public static class Item {
        private Long skuId;
        private Long productId;
        private String productCode;
        private String name;
        private String specValue;
        private String image;
        private Long quantity;
        private Long unitPrice;
        private Long originalPrice;
        private Long lineAmount;
        private Integer availableStock;
        private Boolean valid;
        private String reason;
    }
}
