package com.wuling.finance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("subject_account")
public class SubjectAccount {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long subjectId;
    private String roleType;
    private Long availableBalance;
    private Long frozenBalance;
    private Long totalIncome;
    private Long totalWithdrawn;

    @Version
    private Integer version;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
