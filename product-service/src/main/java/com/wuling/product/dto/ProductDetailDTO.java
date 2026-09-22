package com.wuling.product.dto;

import lombok.Data;

import java.util.List;

@Data
public class ProductDetailDTO {

    private String id;
    private String name;
    private List<String> tags;
    private String description;
    private Long price;
    private Long originalPrice;
    private Long storedValuePrice;
    private String image;
    /** 详情主图（缺省时与 image 相同） */
    private String galleryImage;
    /** 图片免责声明 */
    private String imageDisclaimer;
    /** 促销文案 */
    private String promotionText;
    /** 价格标签 */
    private String priceLabel;
    /** 折扣万分比，10000 = 无折扣 */
    private Integer discountRate;
    /** 详情页标签文案 */
    private String specTag;
    /** 商品角标图标 */
    private String badgeIcon;
    private String ingredients;
    private String allergens;
    private String cupCapacity;
    private List<String> tips;
    private List<MenuDTO.SpecGroup> specGroups;
}
