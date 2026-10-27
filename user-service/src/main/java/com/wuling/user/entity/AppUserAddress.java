package com.wuling.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户收货地址。归属用户由 JWT 或内部调用参数确定，接口不接受客户端覆盖 user_id。
 */
@Data
@TableName("app_user_address")
public class AppUserAddress {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String recipientName;
    private String phone;
    private String provinceCode;
    private String provinceName;
    private String cityCode;
    private String cityName;
    private String districtCode;
    private String districtName;
    private String detailAddress;
    private String tag;
    private Integer isDefault;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}