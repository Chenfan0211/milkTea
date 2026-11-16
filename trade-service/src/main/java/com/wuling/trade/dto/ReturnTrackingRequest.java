package com.wuling.trade.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ReturnTrackingRequest {
    @NotBlank
    @Size(max = 64)
    private String carrier;
    @NotBlank
    @Size(max = 100)
    private String trackingNo;
}
