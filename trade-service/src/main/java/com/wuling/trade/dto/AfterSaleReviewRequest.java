package com.wuling.trade.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** 售后审核备注（通过/拒绝共用）。 */
@Data
public class AfterSaleReviewRequest {

    @Size(max = 255, message = "审核备注不能超过 255 字")
    private String remark;
}
