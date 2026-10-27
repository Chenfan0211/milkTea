package com.wuling.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class MallStockMutationRequest {

    @NotBlank(message = "订单号不能为空")
    private String orderNo;

    /**
     * 业务类型：普通订单默认 ORDER；售后回补传 AFTER_SALE。
     */
    private String bizType;

    /**
     * 业务幂等号：普通订单默认 orderNo；售后回补使用 afterSaleNo。
     */
    private String bizNo;

    @NotEmpty(message = "库存明细不能为空")
    @Valid
    private List<MallStockItemRequest> items;
}

