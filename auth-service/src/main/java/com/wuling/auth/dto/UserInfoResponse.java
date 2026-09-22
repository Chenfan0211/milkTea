package com.wuling.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserInfoResponse {

    private String userId;
    private String userName;
    private List<String> roles;
    private List<String> buttons;
}
