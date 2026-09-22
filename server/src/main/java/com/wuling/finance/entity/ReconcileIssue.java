package com.wuling.finance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对账异常记录。
 *
 * <p>用于记录对账任务发现的资金不一致，供人工核查与处理。
 * 表 {@code reconcile_issue} 在 V1 迁移中已存在，第 8 期补齐实体与服务。
 *
 * <p>设计原则：<b>对账任务只发现问题并记录，不自动修复资金</b>。
 * 资金修复涉及多方账目，自动改账风险高于人工介入 ——
 * 记录 + 告警后由人工确认，必要时再触发补偿动作。
 */
@Data
@TableName("reconcile_issue")
public class ReconcileIssue {

    /** 异常类型：核销后无分账快照 */
    public static final String TYPE_MISSING_SPLIT = "MISSING_SPLIT";
    /** 异常类型：退款后未冲正 */
    public static final String TYPE_MISSING_REVERSE = "MISSING_REVERSE";
    /** 异常类型：分账五方金额与实付不一致 */
    public static final String TYPE_SPLIT_AMOUNT_MISMATCH = "SPLIT_AMOUNT_MISMATCH";

    /** 状态：待处理 */
    public static final String STATUS_OPEN = "OPEN";
    /** 状态：已处理 */
    public static final String STATUS_RESOLVED = "RESOLVED";
    /** 状态：已忽略（人工确认无需处理） */
    public static final String STATUS_IGNORED = "IGNORED";

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 异常类型 */
    private String issueType;
    /** 订单号 */
    private String orderNo;
    /** 系统侧值（如"无快照"、"PENDING"） */
    private String systemValue;
    /** 第三方/预期值（如"应有快照"、"CANCELED"） */
    private String thirdValue;
    /** 差异金额（分），无差异时为 0 */
    private Long diffAmount;
    /** 发现时间 */
    private LocalDateTime foundTime;
    /** 状态 */
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
