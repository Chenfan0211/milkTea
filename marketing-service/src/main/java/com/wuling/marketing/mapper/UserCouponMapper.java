package com.wuling.marketing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.marketing.entity.UserCoupon;
import com.wuling.marketing.dto.CouponExpiryCandidate;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

public interface UserCouponMapper extends BaseMapper<UserCoupon> {

    /**
     * 幂等写入普通领券记录。
     *
     * <p>active_coupon_id 是生成列，不能手工写入；唯一索引
     * uk_user_coupon_active 只拦截 source='RECEIVE' 的同用户同券模板。
     * 返回 0 表示用户已持有该奖励券，调用方应视为发放成功。
     */
    @Insert("insert ignore into user_coupon "
            + "(user_id, coupon_id, status, source, grant_no, receive_time) "
            + "values (#{userId}, #{couponId}, 'UNUSED', 'RECEIVE', 'SINGLE', now())")
    int insertIgnoreActive(@Param("userId") Long userId, @Param("couponId") Long couponId);

    /** 查询指定日期到期、尚未使用的券（含 DAYS 类型动态计算到期日）。 */
    @Select("select uc.id as id, uc.user_id as user_id, uc.coupon_id as coupon_id, c.name as coupon_name, "
            + "c.amount as amount, "
            + "case when c.validity_type = 'DAYS' then "
            + "date_add(date_add(uc.receive_time, interval ifnull(c.effective_delay_days, 0) day), "
            + "interval c.validity_days day) else c.validity_end end as expire_at "
            + "from user_coupon uc join coupon c on c.id = uc.coupon_id "
            + "where uc.status = 'UNUSED' and uc.deleted = 0 and c.status = 'enabled' and c.deleted = 0 "
            + "and date(case when c.validity_type = 'DAYS' then "
            + "date_add(date_add(uc.receive_time, interval ifnull(c.effective_delay_days, 0) day), "
            + "interval c.validity_days day) else c.validity_end end) = #{targetDate} "
            + "order by uc.id limit #{limit}")
    List<CouponExpiryCandidate> selectExpiringUnusedOn(@Param("targetDate") LocalDate targetDate,
                                                       @Param("limit") int limit);
    /** 行锁读取，锁券前置校验与状态转换必须串行。 */
    @Select("select * from user_coupon where id = #{id} and deleted = 0 for update")
    UserCoupon selectByIdForUpdate(@Param("id") Long id);

    /** 仅允许 UNUSED -> LOCKED。 */
    @Update("update user_coupon set status = 'LOCKED', lock_order_id = #{orderId}, update_time = now() "
            + "where id = #{id} and status = 'UNUSED' and deleted = 0")
    int lockUnused(@Param("id") Long id, @Param("orderId") Long orderId);

    /** 仅允许 LOCKED -> USED；重复调用返回 0。 */
    @Update("update user_coupon set status = 'USED', use_time = now(), update_time = now() "
            + "where id = #{id} and status = 'LOCKED' and lock_order_id = #{orderId} and deleted = 0")
    int consumeLocked(@Param("id") Long id, @Param("orderId") Long orderId);

    /** 未支付取消/超时：仅回退指定订单的 LOCKED 券。 */
    @Update("update user_coupon set status = 'UNUSED', lock_order_id = null, update_time = now() "
            + "where id = #{id} and status = 'LOCKED' and lock_order_id = #{orderId} and deleted = 0")
    int releaseLocked(@Param("id") Long id, @Param("orderId") Long orderId);

    /**
     * 退款恢复：仅 USED/LOCKED 可改为目标状态（UNUSED 或 EXPIRED）。
     *
     * <p><b>必须同时清空 use_time</b>：券回到「未使用 / 已过期」后就不再是已使用状态，
     * 留着上次的核销时间会让「状态 ↔ 字段」自相矛盾。
     * 巡检项「非已使用却带 use_time」正是守这条（CouponIntegrityMonitor）。
     */
    @Update("update user_coupon set status = #{toStatus}, lock_order_id = null, use_time = null, update_time = now() "
            + "where id = #{id} and status in ('USED','LOCKED') and deleted = 0")
    int restoreAfterRefund(@Param("id") Long id, @Param("toStatus") String toStatus);

    /** 同一订单是否已经锁定了这张券。 */
    @Select("select count(*) from user_coupon where id = #{id} and user_id = #{userId} "
            + "and status in ('LOCKED','USED') and lock_order_id = #{orderId} and deleted = 0")
    int countLockedByOrder(@Param("id") Long id, @Param("userId") Long userId, @Param("orderId") Long orderId);

    /**
     * 扫描「已过有效期但仍是 UNUSED」的券 id（分批），供过期归档任务使用。
     *
     * <p>DAYS 类型（领取后 N 天）没有 validity_end，过期时间只能现算 ——
     * 口径与 {@code CouponService#deriveTab} 保持一致（延迟生效天数也要算进去）。
     */
    @Select("select uc.id from user_coupon uc join coupon c on c.id = uc.coupon_id "
            + "where uc.status = 'UNUSED' and uc.deleted = 0 "
            + "and ((c.validity_type = 'DAYS' and c.validity_days > 0 and uc.receive_time is not null "
            + "and now() > date_add(date_add(uc.receive_time, interval ifnull(c.effective_delay_days, 0) day), "
            + "interval c.validity_days day)) "
            + "or (c.validity_type = 'RANGE' and c.validity_end is not null and now() > c.validity_end)) "
            + "order by uc.id limit #{limit}")
    List<Long> selectOverdueUnusedIds(@Param("limit") int limit);

    /** 归档单张过期未用券（where status='UNUSED' 保证重复执行幂等）。 */
    @Update("update user_coupon set status = 'EXPIRED', update_time = now() "
            + "where id = #{id} and status = 'UNUSED' and deleted = 0")
    int expireOverdueUnused(@Param("id") Long id);
}
