package com.wuling.marketing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("points_product")
public class PointsProduct {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String image;
    private Long points;
    private Integer stock;
    private String badge;
    private String limitText;
    private String description;
    private String status;
    /** 分类 all/pet/coupon */
    private String category;
    /** 每人限购，0 = 不限 */
    private Integer purchaseLimit;
    /** 券展示类型 fixed/buyone/halfprice */
    private String displayType;
    /** 券面额（分） */
    private Long couponAmount;
    /** 券门槛文案 */
    private String couponCondition;
    /** 角标是否叠加在图片上 */
    private Integer badgeInImage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
