package com.wuling.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("refund")
public class Refund {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String refundNo;
    private Long orderId;
    private String orderNo;
    /** 关联售后单 ID（商城退货退款；普通退款为 null） */
    private Long afterSaleId;
    private Long amount;
    /** 订单实付快照（分），比例冲正用 */
    private Long originalAmount;
    private String status;
    private String reason;
    /** 微信退款单号（受理成功后回填，用于对账/查询） */
    private String thirdRefundNo;
    private LocalDateTime applyTime;
    private LocalDateTime reviewTime;
    private LocalDateTime completeTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
