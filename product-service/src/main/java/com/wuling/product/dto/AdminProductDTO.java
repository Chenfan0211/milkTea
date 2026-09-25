package com.wuling.product.dto;

import lombok.Data;

import java.util.List;

@Data
public class AdminProductDTO {

    private Long id;
    private String productId;
    private String code;
    private String name;
    private String category;
    private Integer specCount;
    private Long price;
    private Long originalPrice;
    /** 成本价（单位：分） */
    private Long costPrice;
    /** 平台分佣（单位：分） */
    private Long platformCommission;
    /** 储值立减金额（单位：分），用储值余额支付时每件商品少多少分 */
    private Long storedValuePrice;
    /** 详情主图 */
    private String galleryImage;
    /** 图片免责声明 */
    private String imageDisclaimer;
    /** 促销文案 */
    private String promotionText;
    private String ingredients;
    private String allergens;
    private String cupCapacity;
    private List<String> tips;
    private String description;
    private String store;
    private List<String> stores;
    private String onSale;
    private String splitReady;
    private String createTime;
}
