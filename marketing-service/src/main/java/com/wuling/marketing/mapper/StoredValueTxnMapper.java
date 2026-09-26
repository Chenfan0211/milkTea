package com.wuling.marketing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.marketing.entity.StoredValueTxn;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface StoredValueTxnMapper extends BaseMapper<StoredValueTxn> {

    /** V55 的唯一键是 biz_no，重复业务号在行锁内核对操作类型和请求参数。 */
    @Select("select * from stored_value_txn where biz_no = #{bizNo} limit 1 for update")
    StoredValueTxn selectByBizNoForUpdate(@Param("bizNo") String bizNo);

    @Update("update stored_value_txn set status = 'SUCCESS', result_message = null, update_time = now() "
            + "where id = #{id} and status = 'PROCESSING'")
    int markSuccess(@Param("id") Long id);

    @Update("update stored_value_txn set status = 'FAILED', result_message = #{reason}, update_time = now() "
            + "where id = #{id} and status = 'PROCESSING'")
    int markFailed(@Param("id") Long id, @Param("reason") String reason);

    @Update("update stored_value_txn set status = 'PROCESSING', result_message = null, update_time = now() "
            + "where id = #{id} and status = 'FAILED'")
    int retryFailed(@Param("id") Long id);
}
