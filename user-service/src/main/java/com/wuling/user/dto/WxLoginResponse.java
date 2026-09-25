package com.wuling.user.dto;

import lombok.Data;

@Data
public class WxLoginResponse {

    private String token;
    private Long userId;
    private String openId;
    private Boolean newUser;
    private String nickName;
    private String avatar;
    private String phone;

    private Boolean registered;

    private String registerToken;
}
