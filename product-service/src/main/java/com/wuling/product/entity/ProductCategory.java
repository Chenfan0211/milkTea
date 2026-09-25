package com.wuling.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("product_category")
public class ProductCategory {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long parentId;
    private String code;
    private String name;
    private String type;
    private Integer sort;
    /** 分类标签（小程序端分类左上角角标） */
    private String tag;
    /** 状态 1启用 0停用 */
    private Integer enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}

