package com.wuling.trade.dto;

import lombok.Data;

import java.util.List;

/** 商城结算确认结果：金额以服务端为唯一事实来源。 */
@Data
public class MallCheckoutQuoteDTO {
    private List<Item> items;
    private Long payableAmount;
    /**
     * 应付金额中「分销服务费」合计（分）。
     *
     * <p>非团长单价 = 售价 + 分销佣金，而商品列表/详情展示的是不含佣金的售价，
     * 两者相差即为本字段。前端据此在结算页展示「含分销服务费」明细，
     * 避免用户看到「列表 ¥10、结账 ¥11」却不知差额来源。
     */
    private Long commissionAmount;
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
        /** 本行单价中包含的分销服务费（分/件）。团长买家为 0。 */
        private Long distributionCommission;
        private Long originalPrice;
        private Long lineAmount;
        private Integer availableStock;
        private Boolean valid;
        private String reason;
    }
}