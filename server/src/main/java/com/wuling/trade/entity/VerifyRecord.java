package com.wuling.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("verify_record")
public class VerifyRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String verifyCode;
    private Long orderId;
    private String orderNo;
    private String type;
    private Long storeSubjectId;
    private String operator;
    private String device;
    private String result;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
