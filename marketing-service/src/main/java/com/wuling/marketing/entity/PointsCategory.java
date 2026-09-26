package com.wuling.marketing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("points_category")
public class PointsCategory {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private Integer sort;
    private Integer enabled;
    /** 1系统分类，禁止改码和删除，名称/排序/启停仍可管理 */
    private Integer systemLocked;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}