package com.wuling.marketing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 储值余额资金操作幂等记录，表由 V55 提供。 */
@Data
@TableName("stored_value_txn")
public class StoredValueTxn {
    public static final String PAY = "PAY";
    public static final String REFUND = "REFUND";
    public static final String COMPENSATE = "COMPENSATE";
    public static final String PROCESSING = "PROCESSING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String bizNo;
    private Long userId;
    private String orderNo;
    private String operationType;
    private Long amount;
    private String status;
    private String requestHash;
    private String resultMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}