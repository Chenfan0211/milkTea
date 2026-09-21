package com.wuling.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("product")
public class Product {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String productId;
    private String code;
    private String name;
    private Long categoryId;
    private String tags;
    private String description;
    private Long price;
    private Long originalPrice;
    private Long storedValuePrice;
    private String image;
    private String ingredients;
    private String allergens;
    private String cupCapacity;
    private String tips;
    private Integer onSale;
    private Long splitRuleId;
    private Long supplierSubjectId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}

