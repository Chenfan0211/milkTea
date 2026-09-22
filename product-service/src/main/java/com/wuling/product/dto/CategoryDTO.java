package com.wuling.product.dto;

import lombok.Data;

@Data
public class CategoryDTO {

    private Long id;
    private Long parentId;
    private String code;
    private String name;
    private String type;
    private Integer sort;
}
