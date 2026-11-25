package com.wuling.finance.payee;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 经营者结算银行卡。
 *
 * <p><b>结算卡 ≠ 提现卡</b>：易宝是两个不同产品。
 * 订单分账路线下，钱进经营者「子商户待结算账户」，只能通过
 * {@code settle/self-settle/apply} 结算到<b>结算卡</b>；提现卡
 * （{@code account/withdraw/card/bind}）是另一条产品线，本表不涉及。
 *
 * <p><b>同名约束</b>（易宝强制，服务层校验）：
 * <ul>
 *   <li>企业 → 只能绑对公账户；</li>
 *   <li>个体工商户 → 对公账户 或 法人借记卡；</li>
 *   <li>小微（自然人）→ 本人借记卡。</li>
 * </ul>
 */
@Data
@TableName("subject_payout_account")
public class PayoutAccount {

    public static final String TYPE_PUBLIC = "PUBLIC";
    public static final String TYPE_PRIVATE = "PRIVATE";

    public static final String VERIFY_PENDING = "PENDING";
    public static final String VERIFY_PASS = "PASS";
    public static final String VERIFY_FAIL = "FAIL";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long subjectId;
    private Long userId;
    private String accountType;
    /** 开户名，须与签约主体一致 */
    private String accountName;
    /** 卡号（AES 加密） */
    private String bankCardNoEnc;
    /** 卡号掩码，展示与去重都用它 */
    private String bankCardMask;
    private String bankCode;
    private String branchCode;
    private String bankName;
    /** 易宝结算卡 ID */
    private String yeepaySettleCardId;
    private String verifyStatus;
    private String verifyMsg;
    private Integer isDefault;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
