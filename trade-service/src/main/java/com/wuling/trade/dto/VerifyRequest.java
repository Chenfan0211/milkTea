package com.wuling.trade.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VerifyRequest {

    @NotBlank(message = "核销码不能为空")
    private String code;

    private String operator;
    private String device;
    /** ORDER：点单核销（按取餐码/订单号）；EXCHANGE：兑换核销 */
    private String type;
}
