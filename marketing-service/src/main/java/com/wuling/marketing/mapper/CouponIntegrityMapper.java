package com.wuling.marketing.mapper;

import org.apache.ibatis.annotations.Select;

/**
 * 优惠券数据完整性巡检查询（只读）。
 *
 * <p>只做「口径明确的硬不变量」计数，供 {@code CouponIntegrityMonitor} 定时巡检告警。
 * 明细排查请看 {@code scripts/check-coupon-integrity.sql}。
 *
 * <p>为什么这些不变量必须有人盯着（2026-10-15 事故复盘）：
 * 小程序券包「已使用」里出现一批根本没有订单的券 —— 它们是历史演示/批量造数
 * <b>直写 user_coupon</b> 留下的；应用代码写不出这种数据（核销必须带 lock_order_id），
 * 所以只能靠巡检主动发现，而不是等用户反馈。
 */
public interface CouponIntegrityMapper {

    /**
     * ① 已使用但没有订单号的券数量。期望值 0。
     *
     * <p>正常核销 {@code UserCouponMapper.consumeLocked} 的 where 要求
     * {@code status='LOCKED' and lock_order_id = #{orderId}}；退款回退
     * {@code restoreAfterRefund} 只会置 UNUSED/EXPIRED 且清空 lock_order_id ——
     * 因此「USED 且 lock_order_id 为空」只可能来自绕过业务的直写库。
     */
    @Select("select count(*) from user_coupon "
            + "where deleted = 0 and status = 'USED' and lock_order_id is null")
    int countUsedWithoutOrder();

    /**
     * ② 同用户同模板存在多张「有效持有券」的用户数。期望值 0。
     *
     * <p>唯一键 {@code uk_user_coupon_active(user_id, active_coupon_id)}（生成列，仅
     * {@code deleted=0 且 status in (UNUSED,LOCKED) 且 source='RECEIVE'} 时才取值）
     * 保证一个用户同一模板只能持有一张有效券。若这里 > 0，
     * 说明索引缺失或被删，并发领券会重复发券。
     */
    @Select("select count(*) from (select user_id, coupon_id from user_coupon "
            + "where deleted = 0 and status in ('UNUSED', 'LOCKED') and source = 'RECEIVE' "
            + "group by user_id, coupon_id having count(*) > 1) t")
    int countDuplicateActiveCoupons();

    /**
     * ③ 非已使用状态却带核销时间的券数量。期望值 0。
     *
     * <p>{@code use_time} 只在 {@code status='USED'} 时有意义：
     * 核销时写入，退款回退（{@code restoreAfterRefund}）清空，
     * 锁定/释放/过期归档都不碰它。出现 > 0 说明有旁路写入或历史脏数据。
     */
    @Select("select count(*) from user_coupon "
            + "where deleted = 0 and status <> 'USED' and use_time is not null")
    int countNonUsedWithUseTime();
}