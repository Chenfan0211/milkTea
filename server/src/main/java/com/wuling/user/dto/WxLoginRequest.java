package com.wuling.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WxLoginRequest {

    @NotBlank(message = "登录凭证不能为空")
    private String code;
}
