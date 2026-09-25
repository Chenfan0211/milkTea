package com.wuling.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 新用户通过微信手机号授权注册（未注册态） */
@Data
public class RegisterByPhoneRequest {

    @NotBlank(message = "注册凭证不能为空")
    private String registerToken;

    @NotBlank(message = "encryptedData 不能为空")
    private String encryptedData;

    @NotBlank(message = "iv 不能为空")
    private String iv;
}