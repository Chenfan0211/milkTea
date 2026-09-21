package com.wuling.finance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("settlement_record")
public class SettlementRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String recordNo;
    private Long subjectId;
    private Long snapshotId;
    private Long orderId;
    private Long amount;
    private String status;
    private LocalDate settleDate;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
