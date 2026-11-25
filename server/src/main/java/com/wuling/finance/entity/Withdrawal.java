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
    /** 三方转账状态（SUCCESS / FAILED / PROCESSING 等） */
    private String transferStatus;
    /** 三方转账失败原因 */
    private String transferFailMsg;

    /**
     * 资金来源渠道：WXPAY / YEEPAY。
     *
     * <p>决定出款渠道——微信收的钱只能走微信零钱，易宝收的钱只能走易宝结算卡。
     * 提现时必须与账户实际余额来源一致，不允许跨池出款。
     */
    private String fundChannel;

    /**
     * 实际出款渠道：MOCK / WXPAY / YEEPAY。
     *
     * <p>与 {@code fundChannel} 的区别：fundChannel 描述「钱从哪来」，
     * payoutChannel 描述「走哪个通道打出去」。正常情况两者相同；
     * mock 联调时 fundChannel 可能是 WXPAY 但 payoutChannel 是 MOCK。
     */
    private String payoutChannel;

    /** 易宝结算卡记录 id（subject_payout_account.id），用于审计追溯 */
    private Long payeeAccountId;

    /** 收款账户快照（JSON）：冻结时固化，防止后续改卡篡改历史凭证 */
    private String payeeSnapshot;

    /** 易宝结算单号（account/withdraw/order 或 self-settle 返回） */
    private String yeepayOrderNo;

    /** 易宝结算状态原始值（SUCCESS / FAIL / PROCESSING / REMITING） */
    private String yeepaySettleStatus;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
