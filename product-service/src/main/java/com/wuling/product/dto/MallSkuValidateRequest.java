package com.wuling.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.List;

@Data
public class MallSkuValidateRequest {

    @NotEmpty(message = "请选择需要校验的 SKU")
    @Valid
    private List<Item> items;

    @Data
    public static class Item {
        @NotNull(message = "SKU ID 不能为空")
        private Long skuId;

        @NotNull(message = "购买数量不能为空")
        @Positive(message = "购买数量必须大于0")
        private Integer quantity;
    }
}
