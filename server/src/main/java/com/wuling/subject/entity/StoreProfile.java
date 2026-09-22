package com.wuling.subject.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("store_profile")
public class StoreProfile {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long subjectId;
    /** 门店业务编码（前端以 code 作为门店 id） */
    private String code;
    private String city;
    private String address;
    private String phone;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String storeType;
    private String businessStatus;
    private String manager;
    private Long investorSubjectId;
    private String businessHours;
    private String modes;
    private String promotion;
    private Integer queueCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
