package com.wuling.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("app_user")
public class AppUser {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String openId;
    private String unionId;
    private String nickName;
    private String avatar;
    private String phone;
    private LocalDate birthday;
    private String gender;
    private String vipLevel;
    private Long points;
    private Long balance;
    private String businessRole;
    private Long boundSubjectId;
    private Long referrerId;
    private Long channelSubjectId;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
