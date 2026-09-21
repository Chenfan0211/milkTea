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
    private String description;
    private String store;
    private List<String> stores;
    private String onSale;
    private String splitReady;
    private String createTime;
}
