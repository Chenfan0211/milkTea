package com.wuling.product.dto;

import lombok.Data;

@Data
public class MallCategoryDTO {
    private Long id;
    private String code;
    private String name;
    private String iconImage;
    private Integer sort;
    private String status;
    private String createTime;
    private String updateTime;
}
