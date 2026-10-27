package com.wuling.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mall_stock_log")
public class MallStockLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long skuId;
    private String orderNo;
    /** 业务类型：ORDER / AFTER_SALE。 */
    private String bizType;
    /** 业务幂等号：订单号或售后单号。 */
    private String bizNo;
    private String changeType;
    private Integer stockDelta;
    private Integer lockedDelta;
    private Integer stockAfter;
    private Integer lockedAfter;
    private String operatorType;
    private String operatorId;
    private String remark;
    private LocalDateTime createTime;
}


