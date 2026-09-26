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

    /**
     * 邀请人用户 ID（可选）。
     *
     * <p>来自邀请分享链接的 referrerId 参数，好友注册成功后写入
     * {@code app_user.referrer_id}，用于记录「是哪个用户分享来的」，
     * 也是后续发放邀请奖励的凭据。
     *
     * <p>可选而非必填：老版本小程序、直接进入注册页等场景不带该参数，
     * 缺失时保持 null（无推荐关系），不应导致注册失败。
     */
    private Long referrerId;
}