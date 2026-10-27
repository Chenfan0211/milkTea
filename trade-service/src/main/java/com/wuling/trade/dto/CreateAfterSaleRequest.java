package com.wuling.trade.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 商城售后申请（仅提交商品与数量，金额由服务端计算）。 */
@Data
public class CreateAfterSaleRequest {

    @NotBlank(message = "售后原因不能为空")
    @Size(max = 255, message = "售后原因不能超过 255 字")
    private String reason;

    @NotEmpty(message = "请选择退货商品")
    private List<Item> items;

    @Data
    public static class Item {
        @NotNull(message = "订单明细不能为空")
        private Long orderItemId;

        @NotNull(message = "退货数量不能为空")
        @Min(value = 1, message = "退货数量至少为 1")
        private Integer quantity;
    }
}
