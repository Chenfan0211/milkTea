package com.wuling.marketing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("points_record")
public class PointsRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String type;
    private Long amount;
    private Long balanceAfter;
    private String source;
    private String orderNo;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
