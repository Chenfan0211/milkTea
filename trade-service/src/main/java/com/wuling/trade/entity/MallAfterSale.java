package com.wuling.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 商城售后单（退货退款申请）。 */
@Data
@TableName("mall_after_sale")
public class MallAfterSale {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String afterSaleNo;
    private Long orderId;
    private String orderNo;
    private Long userId;
    /** PENDING / APPROVED / REJECTED / REFUNDING / SUCCESS / FAILED */
    private String status;
    private String reason;
    /** 退款金额（分） */
    private Long refundAmount;
    /** 退货明细 JSON：[{orderItemId,skuId,skuCode,name,spec,quantity,unitPrice,distributionCommission,lineAmount}] */
    private String items;
    private LocalDateTime applyTime;
    private LocalDateTime reviewTime;
    private String reviewer;
    private LocalDateTime completeTime;
    private Long refundId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
