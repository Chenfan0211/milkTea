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
    /** 微信转账批次号（batch_id，对接「商家转账到零钱」后返回） */
    private String transferBatchNo;
    /** 微信转账状态（SUCCESS / FAILED / PROCESSING 等） */
    private String transferStatus;
    /** 微信转账失败原因 */
    private String transferFailMsg;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
