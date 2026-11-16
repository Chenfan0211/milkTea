package com.wuling.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.trade.entity.Payment;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

public interface PaymentMapper extends BaseMapper<Payment> {
    @Select("SELECT * FROM payment WHERE order_no=#{orderNo} AND biz_type='ORDER' AND deleted=0 "
            + "ORDER BY CASE WHEN standard_status IN ('PAID','REFUNDING','REFUNDED') THEN 0 ELSE 1 END, id DESC LIMIT 1")
    Payment selectCanonicalOrderPayment(@Param("orderNo") String orderNo);

    @Select("SELECT * FROM payment WHERE biz_type='ORDER' AND channel='WXPAY' AND standard_status='PAYING' "
            + "AND deleted=0 AND id>#{afterId} ORDER BY id ASC LIMIT #{limit}")
    List<Payment> selectPayingBatch(@Param("afterId") long afterId, @Param("limit") int limit);

    @Update("UPDATE payment SET standard_status='CLOSED',third_status='SUPERSEDED',update_time=NOW() "
            + "WHERE order_no=#{orderNo} AND biz_type='ORDER' AND id<>#{canonicalId} AND deleted=0 "
            + "AND standard_status IN ('PAYING','FAILED')")
    int closeSupersededAttempts(@Param("orderNo") String orderNo, @Param("canonicalId") Long canonicalId);
}
