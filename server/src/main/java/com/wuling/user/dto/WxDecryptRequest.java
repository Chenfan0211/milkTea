package com.wuling.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 微信加密数据解密入参（手机号 / 用户信息） */
@Data
public class WxDecryptRequest {

    @NotBlank(message = "encryptedData 不能为空")
    private String encryptedData;

    @NotBlank(message = "iv 不能为空")
    private String iv;
}
