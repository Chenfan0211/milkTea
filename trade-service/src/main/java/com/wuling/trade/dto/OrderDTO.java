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
    /** 分账快照摘要（仅后台订单接口下发；未核销订单为 null） */
    private Split split;

    /**
     * 会员价校验结果（仅下单响应带回，查询订单时为 null）。
     *
     * <p>用于把「客户端算的价对不对」明确告知前端：金额一律以服务端为准，
     * 但前端需要知道自己是否口径漂移（例如折扣解析规则被改动），
     * 否则会长期「显示一个价、实收另一个价」而无人察觉。
     */
    private PriceCheck priceCheck;

    /** 会员价校验明细（字段语义见 {@code MemberPriceCheck}） */
    @Data
    public static class PriceCheck {
        /** 客户端提交的金额（分）；未提交时为 null */
        private Long clientAmount;
        /** 服务端权威金额（分），即实际下单金额 */
        private Long serverAmount;
        /** 客户端金额是否与服务端一致 */
        private Boolean correct;
        /** 不一致的原因，便于前端提示与排查；一致时为 null */
        private String reason;
    }

    /**
     * 分账明细（仅后台订单接口下发；小程序订单接口不下发）。
     *
     * <p>数据源为 finance 的 split_snapshot：订单核销后由分账流程落库。
     * 未核销订单没有快照，该字段为 null，前端应自行提示「暂无快照」。
     *
     * <p>字段单位统一为「分」；costTotal / platformCommission 已是
     * 「单价 × 件数」的合计值，前端展示时不要再次乘以件数。
     */
    @Data
    public static class Split {
        /** 分账快照号（未核销时为 null） */
        private String snapshotNo;
        /** 商品件数 */
        private Integer itemCount;
        /** 供应商成本合计（分）= Σ(成本单价 × 件数) */
        private Long costTotal;
        /** 门店所得（分） */
        private Long storeShare;
        /** 资源方所得（分） */
        private Long channelShare;
        /** 投资人所得（分） */
        private Long investorShare;
        /** 平台提成合计（分）= Σ(提成单价 × 件数) */
        private Long platformCommission;
        /** 平台剩余（分）= 实付 − 成本 − 门店 − 资源方 − 投资人 */
        private Long platformShare;
        /** 投资人计费基础额（分），<= 0 时投资人为 0 */
        private Long base;
    }

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
        /**
         * 成本单价（分/件）。
         *
         * <p>取商品当前档案值，非下单时快照：后台分账明细要用它按件数
         * 复算「成本合计 = 成本单价 × 件数」，商品档案缺失时为 null。
         */
        private Long costPrice;
        /** 平台提成单价（分/件），口径同 costPrice */
        private Long platformCommission;
    }
}