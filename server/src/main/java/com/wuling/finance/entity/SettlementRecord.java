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

    /** 未发起分账（默认值，兼容历史数据） */
    public static final String DIVIDE_NONE = "NONE";
    /** 收款方未入驻，钱仍在平台待分账账户，等入驻后补分账 */
    public static final String DIVIDE_PENDING_ONBOARD = "PENDING_ONBOARD";
    /** 分账请求已发出，等待三方最终结果 */
    public static final String DIVIDE_PROCESSING = "PROCESSING";
    /** 分账成功，钱已到收款方子商户账户 */
    public static final String DIVIDE_SUCCESS = "SUCCESS";
    /** 分账失败，需人工核查后重试 */
    public static final String DIVIDE_FAIL = "FAIL";
    /** 暂缓分账（售后期内），到期后再分 */
    public static final String DIVIDE_HOLD = "HOLD";
    /** 待清算：易宝渠道支付成功但清算未完成，此时不能分账 */
    public static final String DIVIDE_PENDING_CLEARING = "PENDING_CLEARING";

    /** 资金来源：微信 */
    public static final String CHANNEL_WXPAY = "WXPAY";
    /** 资金来源：易宝 */
    public static final String CHANNEL_YEEPAY = "YEEPAY";

    /** 清算状态：待清算 */
    public static final String CLEARING_PENDING = "PENDING";
    /** 清算状态：已清算 */
    public static final String CLEARING_SUCCESS = "SUCCESS";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String recordNo;
    private Long subjectId;
    private Long snapshotId;
    private Long orderId;
    /**
     * 订单号（冗余字段）。
     *
     * <p>为什么冗余：渠道可用额计算、分账对账都要按订单号关联
     * {@code payment.channel}，若每次 join {@code split_snapshot} 成本高且
     * 快照表可能被归档。此列由迁移新增并按 order_id 回填。
     */
    private String orderNo;
    private Long amount;
    /** 分账原金额（分），用于多次部分退款累计冲正。 */
    private Long originalAmount;
    /** 已冲正金额（分）。 */
    private Long refundedAmount;
    private String status;
    private LocalDate settleDate;

    /**
     * 易宝分账进度（仅易宝渠道使用；微信渠道恒为 {@code null}）。
     *
     * <p>这是防重复分账的唯一防线：审核重试、任务重跑、回调重复都靠它拦。
     */
    private String divideStatus;
    /** 已分出金额（分），支持部分分账。 */
    private Long dividedAmount;
    /** 分账请求号（商户侧唯一，三方幂等键）。 */
    private String divideRequestId;
    /** 易宝分账流水号。 */
    private String uniqueDivideNo;
    private LocalDateTime divideTime;
    /**
     * 分账冻结到期时间（售后期结束才可分账）。
     *
     * <p>核销时写入 {@code now + hold-days}，期间不发起分账。
     * 售后期内退款时钱还在平台待分账账户，直接退款即可，
     * 无需 {@code divide/back} 向经营者追讨（避免平台垫付）。
     *
     * <p>NULL 表示无需冻结（hold-days=0 或微信渠道）。
     */
    private LocalDateTime divideHoldUntil;

    /**
     * 资金来源渠道：{@code WXPAY} / {@code YEEPAY}。
     *
     * <p>核销时从订单支付渠道（{@code payment.channel}）写入。
     * 渠道可提现额计算、对账、分账路由都依赖这一列，
     * 不再靠联表 {@code payment} 推算（成本高且容易漏）。
     */
    private String fundChannel;

    /**
     * 易宝分账明细单号（{@code divideDetailNo}）。
     *
     * <p><b>为什么必须单独存</b>：{@code divide/apply} 的响应是
     * 「批次 + 明细数组」结构，只有批次级 {@link #uniqueDivideNo}
     * 无法定位到某个接收方。而退款归集要求传<b>明细级</b>单号。
     *
     * <p>没有这一列，部分分账场景将无法退款。
     */
    private String yeepayDivideDetailNo;

    /**
     * 清算状态：{@code PENDING} 待清算 / {@code SUCCESS} 已清算。
     *
     * <p>易宝要求「清算成功后才能分账/退款」，而核销可能早于清算。
     * 未清算时把 {@code divideStatus} 置为
     * {@link #DIVIDE_PENDING_CLEARING}，等清算通知到达再补分，
     * 避免直接调 {@code divide/apply} 撞 {@code OPR21016 订单状态不支持分账}。
     *
     * <p>微信渠道无此概念，恒为 {@code SUCCESS}。
     */
    private String clearingStatus;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
