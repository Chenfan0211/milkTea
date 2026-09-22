package com.wuling.marketing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("coupon")
public class Coupon {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String type;
    private Long amount;
    private Long threshold;
    private String brand;
    private String scenes;
    private String source;
    private String description;
    private String image;
    private String validityType;
    private LocalDateTime validityStart;
    private LocalDateTime validityEnd;
    private Integer validityDays;
    private String usageTime;
    private String applicableStoreIds;
    private String applicableProductIds;
    private Integer stock;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
