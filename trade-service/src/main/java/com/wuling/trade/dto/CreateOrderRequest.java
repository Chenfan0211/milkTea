package com.wuling.trade.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class CreateOrderRequest {

    /**
     * 下单用户 ID。
     *
     * 注意：该字段由服务端从 JWT 注入，客户端传入无效。
     * 因此这里不做 @NotNull 校验——否则校验会在 Controller 方法体执行前触发，
     * 导致「鉴权已通过但校验失败」的误报。
     */
    private Long userId;

    @NotNull(message = "门店不能为空")
    private Long storeSubjectId;

    private Long channelSubjectId;

    @NotBlank(message = "用餐方式不能为空")
    private String mealType;

    private String remark;

    @NotEmpty(message = "订单明细不能为空")
    @Valid
    private List<Item> items;

    @Data
    public static class Item {
        @NotBlank(message = "商品 ID 不能为空")
        private String productId;

        @NotNull(message = "数量不能为空")
        @Min(value = 1, message = "数量至少为 1")
        private Integer quantity;

        /** 规格快照，如 "[中杯,标准冰],加马蹄粉圆" */
        private String spec;
    }
}
