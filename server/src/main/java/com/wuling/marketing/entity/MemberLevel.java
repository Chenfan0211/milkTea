package com.wuling.marketing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("member_level")
public class MemberLevel {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String levelCode;
    private String name;
    private Long amountTarget;
    private String discount;
    private String benefits;
    private Integer sort;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
