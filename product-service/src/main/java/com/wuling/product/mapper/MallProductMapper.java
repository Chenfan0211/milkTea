package com.wuling.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.product.entity.MallProduct;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface MallProductMapper extends BaseMapper<MallProduct> {

    @Select("select * from mall_product where product_code = #{productCode} limit 1")
    MallProduct selectByProductCodeIncludingDeleted(@Param("productCode") String productCode);
}
