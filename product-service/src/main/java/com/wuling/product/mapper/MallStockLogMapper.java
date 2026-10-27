package com.wuling.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.product.entity.MallStockLog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface MallStockLogMapper extends BaseMapper<MallStockLog> {

    @Select("select count(*) from mall_stock_log "
            + "where order_no = #{orderNo} and change_type = #{changeType}")
    boolean existsOrderChange(@Param("orderNo") String orderNo,
                              @Param("changeType") String changeType);

    @Select("select count(*) from mall_stock_log "
            + "where biz_type = #{bizType} and biz_no = #{bizNo} and change_type = #{changeType}")
    boolean existsBizChange(@Param("bizType") String bizType,
                            @Param("bizNo") String bizNo,
                            @Param("changeType") String changeType);

    /** 该 SKU 是否存在任何库存流水（预占/扣减/释放/回补/调整）。 */
    @Select("select count(*) > 0 from mall_stock_log where sku_id = #{skuId}")
    boolean existsBySkuId(@Param("skuId") Long skuId);

    /** 商品下所有 SKU 是否已有库存流水。 */
    @Select("select count(*) > 0 from mall_stock_log l join mall_sku s on s.id = l.sku_id "
            + "where s.product_id = #{productId}")
    boolean existsByProductId(@Param("productId") Long productId);
}


