package com.wuling.product.dto;

import lombok.Data;

@Data
public class MallSkuInternalView {
    private Long skuId;
    private Long productId;
    private String productCode;
    private String productName;
    private String skuCode;
    private String specValue;
    private String image;
    private Long attributionStoreSubjectId;
    private Long attributionChannelSubjectId;
    private Long splitRuleId;
    private Long salePrice;
    private Long originalPrice;
    private Long costPrice;
    private Long platformCommission;
    private Long distributionCommission;
    private Long supplierSubjectId;
    private Integer stock;
    private Integer lockedStock;
    private Integer availableStock;
    private Boolean onSale;
}
