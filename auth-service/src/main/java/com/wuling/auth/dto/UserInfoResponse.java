package com.wuling.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 登录账号信息。
 *
 * <p>{@code isSuper} 为「超级管理员账号」标记（2026-09-25 新增）。
 * 存在的理由是前端需要它来隐藏「账号管理」页里超管行的
 * 编辑/停用/删除/改角色 按钮 —— <b>真正的拦截在服务端</b>
 * （AdminRbacGuard），这里只是避免用户点了才被拒的糟糕体验。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserInfoResponse {

    private String userId;
    private String userName;
    private List<String> roles;
    private List<String> buttons;

    /** 是否超级管理员账号（sys_user.is_super = 1） */
    private Boolean isSuper;
}
