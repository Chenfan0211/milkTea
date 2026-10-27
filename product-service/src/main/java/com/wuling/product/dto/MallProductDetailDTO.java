package com.wuling.product.dto;

import lombok.Data;

import java.util.List;

@Data
public class MallProductDetailDTO {
    private Long id;
    private String productCode;
    private Long categoryId;
    private String categoryName;
    private String name;
    private String subtitle;
    private String specName;
    private String coverImage;
    private List<String> galleryImages;
    private String description;
    private Long minPrice;
    private Long originalPrice;
    private Integer availableStock;
    private Boolean soldOut;
    private Integer salesCount;
    private List<MallSkuView> skus;
}

