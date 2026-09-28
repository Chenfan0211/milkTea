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

    /** 今日待核销订单的杯数（商品件数求和）：状态 PAID、非退款中、今日下单 */
    @Select("select coalesce(sum(i.quantity), 0) from order_item i "
            + "join orders o on o.id = i.order_id and o.deleted = 0 "
            + "where i.deleted = 0 and o.store_subject_id = #{storeSubjectId} "
            + "and o.status = 'PAID' "
            + "and o.create_time >= #{dayStart} "
            + "and (o.refund_status is null or o.refund_status <> 'PENDING')")
    Long sumTodayPendingVerifyQuantity(@Param("storeSubjectId") Long storeSubjectId,
                                       @Param("dayStart") java.time.LocalDateTime dayStart);
}
