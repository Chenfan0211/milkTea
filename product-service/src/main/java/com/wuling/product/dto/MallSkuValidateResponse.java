package com.wuling.product.dto;

import lombok.Data;

import java.util.List;

@Data
public class MallSkuValidateResponse {
    private List<Item> items;

    @Data
    public static class Item {
        private Long skuId;
        private Long productId;
        private String productName;
        private String skuCode;
        private String specValue;
        private String image;
        private Long salePrice;
        private Long distributionCommission;
        private Long originalPrice;
        private Integer quantity;
        private Integer availableStock;
        private Boolean valid;
        private String reason;
    }
}
