package com.wuling.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.finance.entity.SettlementRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SettlementRecordMapper extends BaseMapper<SettlementRecord> {

    @Select("select coalesce(sum(amount),0) from settlement_record where subject_id = #{subjectId} "
            + "and status = 'PENDING' and deleted = 0")
    Long selectSumPending(@Param("subjectId") Long subjectId);

    @Select("select coalesce(sum(amount),0) from settlement_record where subject_id = #{subjectId} "
            + "and status = 'SETTLEABLE' and deleted = 0")
    Long selectSumSettleable(@Param("subjectId") Long subjectId);
}
