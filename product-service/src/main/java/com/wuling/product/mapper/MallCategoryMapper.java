package com.wuling.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.product.entity.MallCategory;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface MallCategoryMapper extends BaseMapper<MallCategory> {

    @Select("select * from mall_category where code = #{code} limit 1")
    MallCategory selectByCodeIncludingDeleted(@Param("code") String code);
}
