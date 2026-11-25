package com.wuling.finance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 提现分账明细（对账留档）。
 *
 * <p><b>为什么需要它</b>：提现出款只调一次自助结算，但「这笔提现对应哪些订单的分账」
 * 这个问题的答案只存在于 {@code settlement_record.divide_status} 里。
 * 而结算记录会被后续退款冲正改写（金额减少、状态置 CANCELED），
 * 历史对应关系会随之失真。
 *
 * <p>本表在提现审核通过时做一次快照留档，之后不再修改，
 * 保证任何时点都能还原「当时这笔提现是由哪些订单撑起来的」。
 */
@Data
@TableName("withdrawal_divide_detail")
public class WithdrawalDivideDetail {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long withdrawalId;
    private String withdrawalNo;
    private Long settlementRecordId;
    private Long orderId;
    private String orderNo;
    private String divideRequestId;
    private Long divideAmount;
    private String divideStatus;
    private String uniqueDivideNo;
    private LocalDateTime createTime;
}
