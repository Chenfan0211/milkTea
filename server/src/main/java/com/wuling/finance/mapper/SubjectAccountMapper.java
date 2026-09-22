package com.wuling.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.finance.entity.SubjectAccount;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface SubjectAccountMapper extends BaseMapper<SubjectAccount> {

    /**
     * 原子冻结（提现申请）：可用余额 -> 冻结余额。
     * 条件 available_balance >= #{amount} 把「校验余额充足 + 扣减」合并为单条 UPDATE，
     * 并发提现时不会超提。
     *
     * @return 1=冻结成功；0=可用余额不足
     */
    @Update("update subject_account "
            + "set available_balance = available_balance - #{amount}, "
            + "    frozen_balance = frozen_balance + #{amount}, "
            + "    version = version + 1, update_time = now() "
            + "where subject_id = #{subjectId} and available_balance >= #{amount} and deleted = 0")
    int freeze(@Param("subjectId") Long subjectId, @Param("amount") long amount);

    /**
     * 原子解冻（提现驳回/失败）：冻结余额 -> 可用余额。
     *
     * @return 1=成功；0=冻结余额不足
     */
    @Update("update subject_account "
            + "set frozen_balance = frozen_balance - #{amount}, "
            + "    available_balance = available_balance + #{amount}, "
            + "    version = version + 1, update_time = now() "
            + "where subject_id = #{subjectId} and frozen_balance >= #{amount} and deleted = 0")
    int unfreeze(@Param("subjectId") Long subjectId, @Param("amount") long amount);

    /**
     * 原子出款（提现成功）：从冻结余额扣减并累计已提现。
     *
     * @return 1=成功；0=冻结余额不足
     */
    @Update("update subject_account "
            + "set frozen_balance = frozen_balance - #{amount}, "
            + "    total_withdrawn = total_withdrawn + #{amount}, "
            + "    version = version + 1, update_time = now() "
            + "where subject_id = #{subjectId} and frozen_balance >= #{amount} and deleted = 0")
    int settleWithdraw(@Param("subjectId") Long subjectId, @Param("amount") long amount);

    /**
     * 原子入账（T+1 结算）：待结算 -> 可用余额。
     *
     * @return 1=成功；0=账户不存在
     */
    @Update("update subject_account "
            + "set available_balance = available_balance + #{amount}, "
            + "    total_income = total_income + #{amount}, "
            + "    version = version + 1, update_time = now() "
            + "where subject_id = #{subjectId} and deleted = 0")
    int creditSettle(@Param("subjectId") Long subjectId, @Param("amount") long amount);
}
