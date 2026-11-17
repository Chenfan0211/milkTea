package com.wuling.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.finance.entity.Withdrawal;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface WithdrawalMapper extends BaseMapper<Withdrawal> {
    /** 审核与失败处理在同一事务内锁定提现单，避免并发审核重复发起出款。 */
    @Select("select * from withdrawal where id = #{id} and deleted = 0 limit 1 for update")
    Withdrawal selectByIdForUpdate(@Param("id") Long id);
}
