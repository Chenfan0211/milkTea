package com.wuling.product.dto;

import lombok.Data;

@Data
public class MallStockAdjustRequest {
    private Integer stock;
    private String remark;
}
