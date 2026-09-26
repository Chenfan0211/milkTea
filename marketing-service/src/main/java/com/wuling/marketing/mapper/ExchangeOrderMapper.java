package com.wuling.marketing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.marketing.entity.ExchangeOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ExchangeOrderMapper extends BaseMapper<ExchangeOrder> {

    /** 汇总用户在同一积分商品上的成功兑换数量。 */
    @Select("select coalesce(sum(quantity), 0) from exchange_order "
            + "where user_id = #{userId} and points_product_id = #{productId} "
            + "and status in ('PENDING', 'VERIFIED', 'COMPLETED') and deleted = 0")
    Long sumSuccessfulQuantity(@Param("userId") Long userId, @Param("productId") Long productId);
}