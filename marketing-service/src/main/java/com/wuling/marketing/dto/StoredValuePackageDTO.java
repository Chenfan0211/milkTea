package com.wuling.marketing.dto;

import lombok.Data;

import java.util.List;

/**
 * 储值套餐（小程序端视图）。
 *
 * <p><b>为什么需要 DTO 而不是直接下发实体</b>：
 * {@code stored_value_package} 实体只有 {@code id/code/name/amount/status}，
 * 而小程序储值页需要展示「赠送优惠券明细」与「使用说明」。
 * 赠券存在关联表 {@code stored_value_package_coupon}，
 * 说明存在 {@code usage_paragraphs} JSON 列 —— 两者都不在实体字段里，
 * 直接下发实体会让前端拿到空数组，页面赠券区恒为空。
 *
 * <p>金额单位统一为<b>分</b>（与后端既有约定一致），
 * 前端负责换算为「元」展示。
 */
@Data
public class StoredValuePackageDTO {

    private Long id;
    private String code;
    private String name;

    /** 储值金额（分） */
    private Long amount;

    private String status;

    /** 赠送优惠券明细（来自 stored_value_package_coupon JOIN coupon） */
    private List<GiftCoupon> coupons;

    /** 使用说明，每行一条；为空时前端回落内置默认文案 */
    private List<String> usageParagraphs;

    /** 套餐赠送的优惠券项 */
    @Data
    public static class GiftCoupon {

        private Long couponId;

        /** 券面额（分） */
        private Long amount;

        /** 每份套餐赠送张数 */
        private Integer quantity;

        /** 展示文案，如「储值赠送-5元代金券」 */
        private String description;
    }
}
