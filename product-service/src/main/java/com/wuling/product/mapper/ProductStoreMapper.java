package com.wuling.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.product.entity.ProductStore;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface ProductStoreMapper extends BaseMapper<ProductStore> {

    /**
     * 物理删除某商品的全部门店关联。
     *
     * <p><b>为什么必须物理删除</b>：product_store 上有唯一键
     * {@code uk_product_store(product_id, store_subject_id)}。
     * MyBatis-Plus 的 {@code delete(...)} 受 {@code @TableLogic} 影响，
     * 实际执行的是 {@code UPDATE ... SET deleted = 1}，旧行仍占用唯一键，
     * 导致「先删后重新添加同一门店」时 insert 触发
     * {@code Duplicate entry 'x-y' for key 'product_store.uk_product_store'}。
     *
     * <p>关联表无审计需求，故用原生 DELETE 绕过逻辑删除。
     */
    @Delete("DELETE FROM product_store WHERE product_id = #{productId}")
    int physicalDeleteByProductId(@Param("productId") Long productId);
}
