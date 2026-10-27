package com.wuling.product.dto;

import lombok.Data;

@Data
public class MallProductCardDTO {
    private Long id;
    private String productCode;
    private Long categoryId;
    private String name;
    private String subtitle;
    private String coverImage;
    private Long minPrice;
    private Long originalPrice;
    private Integer availableStock;
    private Boolean soldOut;
    private Integer sort;
}


