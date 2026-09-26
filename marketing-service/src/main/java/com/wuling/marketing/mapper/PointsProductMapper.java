package com.wuling.marketing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.marketing.entity.PointsProduct;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface PointsProductMapper extends BaseMapper<PointsProduct> {

    /** 原子扣减 1 件积分商品库存；新兑换逻辑应传 quantity。 */
    @Update("update points_product set stock = stock - 1, update_time = now() "
            + "where id = #{productId} and stock > 0 and status = 'enabled' and deleted = 0")
    int deductStock(@Param("productId") Long productId);

    /**
     * 原子扣减指定数量库存。
     *
     * @return 1=扣减成功；0=库存不足或商品已停用
     */
    @Update("update points_product set stock = stock - #{quantity}, update_time = now() "
            + "where id = #{productId} and stock >= #{quantity} "
            + "and status = 'enabled' and deleted = 0")
    int deductStock(@Param("productId") Long productId, @Param("quantity") Integer quantity);

    /** 锁定积分商品行，串行化库存和限购判断。 */
    @Select("select * from points_product where id = #{productId} and deleted = 0 for update")
    PointsProduct selectByIdForUpdate(@Param("productId") Long productId);
}