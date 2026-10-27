package com.wuling.product.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class MallSkuQueryRequest {

    @NotEmpty(message = "skuIds 不能为空")
    private List<Long> skuIds;
}
