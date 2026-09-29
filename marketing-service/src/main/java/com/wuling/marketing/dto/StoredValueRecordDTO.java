package com.wuling.marketing.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 储值流水视图（小程序「储值记录」页）。
 *
 * <p>把「充值」（stored_value_order，PAID）与「消费/退款」
 * （stored_value_txn，SUCCESS）归并为统一时间倒序流水，
 * 前端拿到后按 type 渲染标题与金额正负。
 *
 * <p>金额单位统一为<b>分</b>，前端换算为「元」。
 */
@Data
public class StoredValueRecordDTO {

    /** 流水类型：RECHARGE / CONSUME / REFUND */
    private String type;

    /** 展示标题：充值 / 余额支付 / 余额退回 */
    private String title;

    /** 金额（分，正数） */
    private Long amount;

    /** 业务单号（储值订单号或点单订单号），用于追溯 */
    private String bizNo;

    /** 发生时间 */
    private LocalDateTime time;
}
