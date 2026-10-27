package com.wuling.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.trade.entity.Order;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

public interface OrderMapper extends BaseMapper<Order> {

    /** 门店绑定的渠道（首个），用于渠道永久归因 */
    @Select("select channel_subject_id from channel_store where store_subject_id = #{storeSubjectId} "
            + "and deleted = 0 order by id limit 1")
    Long selectBoundChannel(@Param("storeSubjectId") Long storeSubjectId);

    /**
     * 门店近 1 小时待核销订单的杯数（商品件数求和）：状态 PAID、非退款中、支付时间在窗口内。
     *
     * <p>为什么用「支付时间 + 滑动窗口」而不是「下单时间 + 自然日」：
     * 门店排队是「此刻还有多少杯要做」的瞬时量，属于近实时指标。
     * 自然日口径会在 00:00 把「昨天下单、现在还排着」的在制单直接清零，
     * 也会把当天早上的单算到深夜，与该文案的展示语义不符。
     *
     * <p>为什么不能用 status=COMPLETED 判「已核销未取餐」：奶茶核销即取餐，
     * {@code VerifyService#markVerified} 只写 status/verify_time，complete_time
     * 永远为 NULL，会把历史全部已核销订单当成在制（历史事故：某店显示「前方28杯」）。
     */
    @Select("select coalesce(sum(i.quantity), 0) from order_item i "
            + "join orders o on o.id = i.order_id and o.deleted = 0 "
            + "where i.deleted = 0 and o.order_type = 'STORE' "
            + "and o.store_subject_id = #{storeSubjectId} "
            + "and o.status = 'PAID' "
            + "and o.pay_time >= #{windowStart} "
            + "and (o.refund_status is null or o.refund_status <> 'PENDING')")
    Long sumRecentPendingVerifyQuantity(@Param("storeSubjectId") Long storeSubjectId,
                                        @Param("windowStart") java.time.LocalDateTime windowStart);

    /**
     * 批量统计多个门店近 1 小时待核销杯数（一条 GROUP BY，消除逐店调用）。
     *
     * <p>与单店版 {@link #sumRecentPendingVerifyQuantity} 口径完全一致，
     * 仅增加 store_subject_id 分组，用于门店列表一次性取回全部门店队列数，
     * 避免「N 家门店 -> N 次跨服务 HTTP + N 次 SQL」的 N+1。
     *
     * @return 每行 {store_subject_id, qty}；无订单的门店不会出现在结果中，调用方按 0 处理
     */
    @Select("<script>"
            + "select o.store_subject_id as storeSubjectId, coalesce(sum(i.quantity), 0) as qty "
            + "from order_item i "
            + "join orders o on o.id = i.order_id and o.deleted = 0 "
            + "where i.deleted = 0 and o.order_type = 'STORE' "
            + "and o.status = 'PAID' "
            + "and o.pay_time &gt;= #{windowStart} "
            + "and (o.refund_status is null or o.refund_status &lt;&gt; 'PENDING') "
            + "and o.store_subject_id in "
            + "<foreach collection='storeSubjectIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "group by o.store_subject_id"
            + "</script>")
    List<Map<String, Object>> sumRecentPendingVerifyQuantities(
            @Param("storeSubjectIds") List<Long> storeSubjectIds,
            @Param("windowStart") java.time.LocalDateTime windowStart);
}