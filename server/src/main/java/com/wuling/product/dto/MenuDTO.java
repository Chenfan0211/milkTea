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
        private Long storedValuePrice;
        private String image;
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
