package com.wuling.marketing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.marketing.dto.ExchangeOrderView;
import com.wuling.marketing.entity.ExchangeOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ExchangeOrderMapper extends BaseMapper<ExchangeOrder> {

    /** 汇总用户在同一积分商品上的成功兑换数量。 */
    @Select("select coalesce(sum(quantity), 0) from exchange_order "
            + "where user_id = #{userId} and points_product_id = #{productId} "
            + "and status in ('PENDING', 'VERIFIED', 'COMPLETED') and deleted = 0")
    Long sumSuccessfulQuantity(@Param("userId") Long userId, @Param("productId") Long productId);

    /**
     * 我的兑换记录（联表补齐商品名/图片）。
     *
     * <p>用 left join 而非 inner join：商品被逻辑删除后，历史兑换记录仍要能展示，
     * 此时 product_name 为 null，由前端兜底文案处理。
     */
    @Select("select e.id, e.exchange_no, e.points_product_id, p.name as product_name, "
            + "p.image as product_image, e.points, e.quantity, e.pickup_code, e.status, e.create_time "
            + "from exchange_order e left join points_product p on p.id = e.points_product_id "
            + "where e.user_id = #{userId} and e.deleted = 0 order by e.id desc")
    List<ExchangeOrderView> listMyExchangeOrders(@Param("userId") Long userId);
}
