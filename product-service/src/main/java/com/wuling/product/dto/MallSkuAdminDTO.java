package com.wuling.product.dto;

import lombok.Data;

@Data
public class MallSkuAdminDTO {
    private Long id;
    private String skuCode;
    private String specValue;
    private String image;
    private Long salePrice;
    private Long originalPrice;
    private Long costPrice;
    private Long platformCommission;
    private Long distributionCommission;
    private Long supplierSubjectId;
    private String supplierName;
    private Integer stock;
    private Integer lockedStock;
    private Integer availableStock;
    private Integer sort;
    private Integer version;
}
