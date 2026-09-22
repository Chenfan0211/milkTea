package com.wuling.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("product")
public class Product {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String productId;
    private String code;
    private String name;
    private Long categoryId;
    private String tags;
    private String description;
    private Long price;
    private Long originalPrice;
    private Long storedValuePrice;
    private String image;
    /** 详情主图 */
    private String galleryImage;
    /** 图片免责声明 */
    private String imageDisclaimer;
    /** 促销文案 */
    private String promotionText;
    /** 价格标签（如 小程序价） */
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
    private String tips;
    private Integer onSale;
    private Long splitRuleId;
    private Long supplierSubjectId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
