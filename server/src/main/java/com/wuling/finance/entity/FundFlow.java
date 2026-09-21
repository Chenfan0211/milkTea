package com.wuling.finance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("fund_flow")
public class FundFlow {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String flowNo;
    private Long subjectId;
    private String roleType;
    private String type;
    private String direction;
    private Long amount;
    private String orderNo;
    private Long balanceAfter;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
