package com.wuling.finance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("subject_account")
public class SubjectAccount {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long subjectId;
    private String roleType;
    private Long availableBalance;
    private Long frozenBalance;
    /**
     * 押金余额（分）。
     *
     * <p><b>不可提现、不可分账</b>，仅用于退款冲正兜底：当可用余额不足以扣回
     * 退款份额时，从押金补扣，避免退款直接失败。运营在后台设定，默认 0。
     *
     * <p>为什么押金不分账到子商户：钱一旦进入经营者子商户账户，他可以在易宝
     * 商户后台自行结算提走，押金就失去兜底作用。
     */
    private Long depositBalance;
    private Long totalIncome;
    private Long totalWithdrawn;

    @Version
    private Integer version;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
