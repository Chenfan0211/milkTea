package com.wuling.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.trade.entity.MallAfterSale;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface MallAfterSaleMapper extends BaseMapper<MallAfterSale> {

    @Select("select * from mall_after_sale where order_id = #{orderId} "
            + "and status in ('PENDING','APPROVED','REFUNDING') and deleted = 0 "
            + "order by id desc limit 1 for update")
    MallAfterSale selectActiveByOrderIdForUpdate(@Param("orderId") Long orderId);

    @Select("select * from mall_after_sale where order_id = #{orderId} and deleted = 0 "
            + "order by id desc limit 1")
    MallAfterSale selectLatestByOrderId(@Param("orderId") Long orderId);

    @Select("select * from mall_after_sale where after_sale_no = #{afterSaleNo} and deleted = 0 limit 1")
    MallAfterSale selectByAfterSaleNo(@Param("afterSaleNo") String afterSaleNo);

    /** 审核入口：锁定售后单，防止并发重复审核生成两笔退款。 */
    @Select("select * from mall_after_sale where after_sale_no = #{afterSaleNo} and deleted = 0 limit 1 for update")
    MallAfterSale selectByAfterSaleNoForUpdate(@Param("afterSaleNo") String afterSaleNo);

    /**
     * 已成功或退款中的退货数量：两者都占用可退额度。
     * FAILED 不占用额度，用户可继续申请或复用原退款重试。
     */
    @Select("select coalesce(sum(cast(json_extract(item.quantity, '$') as unsigned)), 0) "
            + "from mall_after_sale a, json_table(a.items, '$[*]' columns("
            + "order_item_id bigint path '$.orderItemId', quantity int path '$.quantity')) item "
            + "where a.order_id = #{orderId} and a.deleted = 0 "
            + "and a.status in ('APPROVED','REFUNDING','SUCCESS') "
            + "and item.order_item_id = #{orderItemId}")
    int selectSumRefundedQuantity(@Param("orderId") Long orderId,
                                  @Param("orderItemId") Long orderItemId);
}


