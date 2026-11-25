package com.wuling.subject.onboard;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 经营者易宝商户入驻与签约记录。
 *
 * <p>一主体一条（{@code uk_onboard_subject}）。易宝同一请求号在「申请已驳回」前
 * 不允许重复发起，故换请求号重提时复用本行，只更新 {@code requestNo}。
 */
@Data
@TableName("subject_merchant_onboard")
public class MerchantOnboard {

    /** 审核中 */
    public static final String STATUS_REVIEWING = "REVIEWING";
    /** 已驳回 */
    public static final String STATUS_REVIEW_BACK = "REVIEW_BACK";
    /** 业务开通中 */
    public static final String STATUS_BUSINESS_OPENING = "BUSINESS_OPENING";
    /** 已完成（可提现） */
    public static final String STATUS_COMPLETED = "COMPLETED";

    /** 企业 */
    public static final String SIGN_ENTERPRISE = "ENTERPRISE";
    /** 个体工商户 */
    public static final String SIGN_INDIVIDUAL = "INDIVIDUAL";
    /** 小微（自然人） */
    public static final String SIGN_MICRO = "MICRO";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long subjectId;
    private Long userId;
    private String requestNo;
    private String applicationNo;
    /** 易宝子商户编号：分账时作为 ledgerNo */
    private String yeepayMerchantNo;
    private String signType;
    private String businessRole;
    private String applicationStatus;
    /** 电子签章地址（入网结果通知下发） */
    private String signUrl;
    private LocalDateTime signedTime;
    private String rejectReason;
    private String notifyRaw;
    private Integer retryCount;
    private LocalDateTime lastQueryTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
