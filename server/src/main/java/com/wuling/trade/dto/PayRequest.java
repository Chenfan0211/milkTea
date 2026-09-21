package com.wuling.trade.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PayRequest {

    @NotBlank(message = "支付渠道不能为空")
    private String channel;

    /** 幂等键：同一订单同一 key 重复提交只生效一次 */
    private String idempotentKey;

    @NotNull(message = "支付金额不能为空")
    @Min(value = 1, message = "支付金额必须大于 0")
    private Long amount;
}
