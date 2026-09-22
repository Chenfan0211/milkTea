package com.wuling.marketing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.marketing.entity.PointsProduct;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface PointsProductMapper extends BaseMapper<PointsProduct> {

    /**
     * 原子扣减积分商品库存（防超兑）。
     *
     * @return 1=扣减成功；0=库存不足或商品已停用
     */
    @Update("update points_product set stock = stock - 1, update_time = now() "
            + "where id = #{productId} and stock > 0 and status = 'enabled' and deleted = 0")
    int deductStock(@Param("productId") Long productId);
}
