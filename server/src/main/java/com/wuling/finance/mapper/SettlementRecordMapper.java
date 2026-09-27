package com.wuling.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.finance.entity.SettlementRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface SettlementRecordMapper extends BaseMapper<SettlementRecord> {

    /**
     * 按订单锁定全部结算记录，供退款冲正串行执行。
     *
     * <p>退款消息可能由 Broker 重复投递，Redis 幂等键也可能因缺失或过期失效。
     * 冲正必须在数据库层按订单加行锁，并在写入流水前通过条件状态更新抢占记录。
     */
    @Select("select * from settlement_record "
            + "where order_id = #{orderId} and deleted = 0 "
            + "order by subject_id, id for update")
    List<SettlementRecord> selectByOrderIdForUpdate(@Param("orderId") Long orderId);

    /** 仅当状态仍为 expectedStatus 时流转，防止并发重复冲正。 */
    @Update("update settlement_record set status = #{targetStatus}, update_time = now() "
            + "where id = #{id} and status = #{expectedStatus} and deleted = 0")
    int updateStatusIfCurrent(@Param("id") Long id,
                              @Param("expectedStatus") String expectedStatus,
                              @Param("targetStatus") String targetStatus);

    @Select("select coalesce(sum(amount),0) from settlement_record where subject_id = #{subjectId} "
            + "and status = 'PENDING' and deleted = 0")
    Long selectSumPending(@Param("subjectId") Long subjectId);

    @Select("select coalesce(sum(amount),0) from settlement_record where subject_id = #{subjectId} "
            + "and status = 'SETTLEABLE' and deleted = 0")
    Long selectSumSettleable(@Param("subjectId") Long subjectId);

    /**
     * 查询投资人**当月**累计已分账金额（分），用于「当月达标后比例」判定。
     *
     * <p>口径说明：
     * <ul>
     *   <li>只统计该投资人作为收款方（subject_id）的记录；</li>
     *   <li>不限定结算状态 —— 只要已分账即计入「累计」，达标衡量的是业务贡献而非到账进度；</li>
     *   <li>按 create_time 落在当月自然月内统计，跨月自动归零，符合「当月目标」语义。</li>
     * </ul>
     *
     * <p>为何用 create_time 而非 settle_date：分账记录生成当下即代表当月业绩完成，
     * 而 settle_date 是 T+1 结算日期，用它会导致月末订单被算进下月。
     */
    @Select("select coalesce(sum(amount),0) from settlement_record "
            + "where subject_id = #{subjectId} and deleted = 0 "
            + "and create_time >= #{monthStart} and create_time < #{monthEnd}")
    Long selectSumCurrentMonth(@Param("subjectId") Long subjectId,
                               @Param("monthStart") java.time.LocalDateTime monthStart,
                               @Param("monthEnd") java.time.LocalDateTime monthEnd);
}