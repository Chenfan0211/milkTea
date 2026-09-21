package com.wuling.finance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("withdrawal")
public class Withdrawal {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String withdrawNo;
    private Long userId;
    private Long subjectId;
    private String roleType;
    private Long amount;
    private Long fee;
    private String status;
    private LocalDateTime applyTime;
    private LocalDateTime reviewTime;
    private LocalDateTime payTime;
    private LocalDateTime callbackTime;
    private String failureReason;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
