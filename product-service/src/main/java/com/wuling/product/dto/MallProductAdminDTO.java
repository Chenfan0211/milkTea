package com.wuling.product.dto;

import lombok.Data;

import java.util.List;

@Data
public class MallProductAdminDTO {
    private Long id;
    private String productCode;
    private Long categoryId;
    private String categoryName;
    private String name;
    private String subtitle;
    private String specName;
    private String coverImage;
    private List<String> galleryImages;
    private List<String> detailImages;
    private String description;
    private Long supplierSubjectId;
    private String supplierName;
    private String status;
    /** 运营排序号（越小越靠前），影响小程序商城列表顺序。 */
    private Integer sort;
    private Integer salesCount;
    private Long minPrice;
    private Long maxPrice;
    private Integer availableStock;
    /** 是否允许删除；有历史订单或库存流水时为 false（仅允许下架）。 */
    private Boolean deletable;
    /** 禁止删除的原因，可删除时为 null。 */
    private String deleteBlockedReason;
    private String createTime;
    private String updateTime;
    private List<MallSkuAdminDTO> skus;
}

