package com.wuling.trade.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.List;

/** 商城结算确认：服务端按当前用户身份计算成交价与应付金额。 */
@Data
public class MallCheckoutQuoteRequest {

    @NotEmpty(message = "请选择需要结算的商品")
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
