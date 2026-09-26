package com.wuling.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 余额支付意图（跨服务「扣款-落库」补偿对账用）。
 *
 * <p><b>为什么需要它</b>：余额支付要在 trade 域一次事务里完成
 * 「调 marketing 扣余额 → 置订单已支付 → 写支付单」，但扣余额发生在
 * marketing 库（远程、独立事务、立即提交），订单落库在 trade 库（本地事务）。
 * 若本地事务在扣款成功后回滚，会留下「已扣款但订单未支付」的悬挂单。
 *
 * <p>意图记录在<b>扣款前</b>用 {@code REQUIRES_NEW} 独立提交，
 * 即便主事务回滚，意图仍在；补偿任务据此扫悬挂单并回冲余额。
 *
 * <p><b>幂等键</b>：{@code orderNo} —— 一张订单同时只允许一笔未完结的余额支付意图。
 */
@Data
@TableName("balance_pay_intent")
public class BalancePayIntent {

    /** 状态：扣款已发起、待确认订单是否已支付 */
    public static final String STATUS_PENDING = "PENDING";
    /** 状态：订单已支付（扣款-落库两段都成功，正常终态） */
    public static final String STATUS_DONE = "DONE";
    /** 状态：已回冲（发现悬挂单后把余额退回） */
    public static final String STATUS_COMPENSATED = "COMPENSATED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private Long userId;
    private Long amount;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}