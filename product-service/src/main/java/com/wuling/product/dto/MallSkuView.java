package com.wuling.product.dto;

import lombok.Data;

@Data
public class MallSkuView {
    private Long id;
    private String skuCode;
    private String specValue;
    private String image;
    private Long salePrice;
    private Long distributionCommission;
    private Long originalPrice;
    private Integer availableStock;
    private Boolean soldOut;
}

