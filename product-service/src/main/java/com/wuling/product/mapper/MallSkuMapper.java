package com.wuling.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.product.entity.MallSku;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface MallSkuMapper extends BaseMapper<MallSku> {

    @Select("select * from mall_sku where sku_code = #{skuCode} limit 1")
    MallSku selectBySkuCodeIncludingDeleted(@Param("skuCode") String skuCode);

    @Select("select * from mall_sku where id = #{id} and deleted = 0 for update")
    MallSku selectByIdForUpdate(@Param("id") Long id);

    @Select("select * from mall_sku where id = #{id} for update")
    MallSku selectByIdForUpdateIncludingDeleted(@Param("id") Long id);

    @Update("update mall_sku set stock = #{stock}, locked_stock = #{lockedStock}, "
            + "version = version + 1, update_time = now() "
            + "where id = #{skuId} and deleted = 0")
    int updateStockState(@Param("skuId") Long skuId,
                         @Param("stock") Integer stock,
                         @Param("lockedStock") Integer lockedStock);

    @Update("update mall_sku set stock = #{stock}, locked_stock = #{lockedStock}, "
            + "version = version + 1, update_time = now() where id = #{skuId}")
    int updateStockStateIncludingDeleted(@Param("skuId") Long skuId,
                                         @Param("stock") Integer stock,
                                         @Param("lockedStock") Integer lockedStock);
}
