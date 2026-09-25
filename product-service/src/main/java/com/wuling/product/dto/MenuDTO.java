package com.wuling.product.dto;

import lombok.Data;

import java.util.List;

public class MenuDTO {

    @Data
    public static class MenuTab {
        private String id;
        private String label;
        private List<MenuGroup> groups;
    }

    @Data
    public static class MenuGroup {
        private String id;
        private String label;
        private List<MenuCategory> categories;
    }

    @Data
    public static class MenuCategory {
        private String id;
        private String label;
        /** 分类左上角标签 */
        private String tag;
        private List<MenuProduct> products;
    }

    @Data
    public static class MenuProduct {
        private String id;
        private String name;
        private List<String> tags;
        private String description;
        private Long price;
        private Long originalPrice;
        /** 储值立减金额（单位：分） */
        private Long storedValuePrice;
        private String image;
        /** 详情主图 */
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
        private List<SpecGroup> specGroups;
    }

    @Data
    public static class SpecGroup {
        private String id;
        private String label;
        private List<SpecOption> options;
    }

    @Data
    public static class SpecOption {
        private String id;
        private String label;
        private Boolean selected;
        private Long priceDelta;
        private String icon;
    }
}

