package com.wuling.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.trade.entity.Order;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface OrderMapper extends BaseMapper<Order> {

    /** 门店绑定的渠道（首个），用于渠道永久归因 */
    @Select("select channel_subject_id from channel_store where store_subject_id = #{storeSubjectId} "
            + "and deleted = 0 order by id limit 1")
    Long selectBoundChannel(@Param("storeSubjectId") Long storeSubjectId);
}
