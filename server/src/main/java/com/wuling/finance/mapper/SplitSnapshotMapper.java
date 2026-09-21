package com.wuling.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.finance.entity.SplitSnapshot;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SplitSnapshotMapper extends BaseMapper<SplitSnapshot> {

    @Select("select order_id from split_snapshot where order_no = #{orderNo} and deleted = 0 limit 1")
    Long selectOrderIdByNo(@Param("orderNo") String orderNo);
}
