package com.wuling.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;
    private String password;
    private String nickName;
    private Integer status;

    /**
     * 是否超级管理员账号（V38 新增列）。
     *
     * <p>用于「超管账号不可改删、仅可重置密码」的判定；
     * 由 AdminAccountController 在角色变更时按是否绑定 R_SUPER 同步维护。
     */
    private Integer isSuper;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}

