package com.wuling.trade.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreateMallOrderRequest {
    @NotBlank(message = "下单请求标识不能为空")
    @Size(max = 64, message = "下单请求标识过长")
    private String clientRequestId;

    @NotNull(message = "请选择收货地址")
    private Long addressId;

    /** WXPAY or STORED_VALUE. */
    private String payChannel;

    @NotEmpty(message = "订单明细不能为空")
    @Valid
    private List<Item> items;

    @Data
    public static class Item {
        @NotNull(message = "SKU 不能为空")
        private Long skuId;

        @NotNull(message = "数量不能为空")
        @Min(value = 1, message = "数量至少为 1")
        private Integer quantity;
    }
}
