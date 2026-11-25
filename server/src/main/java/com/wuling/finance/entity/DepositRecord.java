package com.wuling.finance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 经营者押金台账。
 *
 * <p>每次押金变动（运营设定/调整、退款抵扣、抵扣返还）都写一条，
 * 与 {@code fund_flow} 双写：
 * <ul>
 *   <li>{@code fund_flow}：统一资金流水视图，参与总额对账；</li>
 *   <li>{@code deposit_record}：押金专项台账，带 {@code reason} 便于向经营者解释。</li>
 * </ul>
 *
 * <p>{@code reason} 必须写清订单号与退款单号，否则经营者只看到「押金少了」
 * 却不知原因，极易引发纠纷。
 */
@Data
@TableName("deposit_record")
public class DepositRecord {

    /** 运营设定（首次或增加） */
    public static final String BIZ_SET = "SET";
    /** 运营调整 */
    public static final String BIZ_ADJUST = "ADJUST";
    /** 售后退款抵扣 */
    public static final String BIZ_DEDUCT_REFUND = "DEDUCT_REFUND";
    /** 抵扣返还（退款撤销等场景） */
    public static final String BIZ_REFUND_BACK = "REFUND_BACK";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String recordNo;
    private Long subjectId;
    /** 变动金额（分），正增负减 */
    private Long changeAmount;
    private Long balanceBefore;
    private Long balanceAfter;
    private String bizType;
    private String bizNo;
    private String orderNo;
    private String operator;
    private String reason;
    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}
