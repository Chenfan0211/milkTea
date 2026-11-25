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
    /** 分类 id（编辑回显用；与 category 同源，避免前端按名称反查） */
    private Long categoryId;
    /** 商品标签（JSON 数组解析后的字符串列表，与小程序菜单口径一致） */
    private List<String> tags;
    private Integer specCount;
    private Long price;
    private Long originalPrice;
    /** 成本价（单位：分） */
    private Long costPrice;
    /** 平台分佣（单位：分） */
    private Long platformCommission;
    /** 储值立减金额（单位：分），用储值余额支付时每件商品少多少分 */
    private Long storedValuePrice;
    /**
     * 商品缩略图（点单菜单与后台列表共用）。
     *
     * <p>未上传时原样返回空，不回落到 galleryImage：后台需要能看出
     * 「哪些商品缩略图没传」，列表展示的回退由前端按 image -> galleryImage
     * -> 占位图 的顺序处理。
     */
    private String image;
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
    /** 运营排序号（越小越靠前），小程序点单菜单按此排序。 */
    private Integer sort;
    private String splitReady;
    private String createTime;
}
