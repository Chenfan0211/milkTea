package com.wuling.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 新用户通过短信验证码注册（未注册态） */
@Data
public class RegisterBySmsRequest {

    @NotBlank(message = "注册凭证不能为空")
    private String registerToken;

    @NotBlank(message = "手机号不能为空")
    private String phone;

    @NotBlank(message = "验证码不能为空")
    private String code;

    /** 邀请人用户 ID（可选），语义见 {@link RegisterByPhoneRequest#getReferrerId()} */
    private Long referrerId;
}