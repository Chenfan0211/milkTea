package com.wuling.marketing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
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
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
