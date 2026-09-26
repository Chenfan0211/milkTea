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

    /**
     * 会员等级代码（如 Lv1），由客户端传入，仅用于与服务端记录交叉校验。
     *
     * <p><b>不参与计价决策</b>：服务端一律以 {@code app_user.vip_level} 为准。
     * 若直接采信本字段，用户改包成 Lv3 即可自选 6 折 —— 属越权。
     * 两者不一致时只记 WARN 日志，仍按服务端等级计价。
     */
    private String vipLevel;

    /**
     * 客户端计算的会员价总额（分），仅用于交叉校验。
     *
     * <p>服务端会用「商品原价 × 等级折扣」重算，并在响应里通过
     * {@code priceCheck} 告知客户端算得对不对；<b>下单金额永远以服务端为准</b>。
     * 传 null 表示客户端不做校验（服务端仍按自己算的金额下单）。
     */
    private Long clientAmount;

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
