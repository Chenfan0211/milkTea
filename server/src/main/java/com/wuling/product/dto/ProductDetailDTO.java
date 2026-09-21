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
    private String ingredients;
    private String allergens;
    private String cupCapacity;
    private List<String> tips;
    private List<MenuDTO.SpecGroup> specGroups;
}
