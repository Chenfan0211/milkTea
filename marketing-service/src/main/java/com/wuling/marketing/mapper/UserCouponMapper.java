package com.wuling.marketing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.marketing.entity.UserCoupon;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface UserCouponMapper extends BaseMapper<UserCoupon> {

    /**
     * 幂等写入普通领券记录。
     *
     * <p>active_coupon_id 是生成列，不能手工写入；唯一索引
     * uk_user_coupon_active 只拦截 source='RECEIVE' 的同用户同券模板。
     * 返回 0 表示用户已持有该奖励券，调用方应视为发放成功。
     */
    @Insert("insert ignore into user_coupon "
            + "(user_id, coupon_id, status, source, receive_time) "
            + "values (#{userId}, #{couponId}, 'UNUSED', 'RECEIVE', now())")
    int insertIgnoreActive(@Param("userId") Long userId, @Param("couponId") Long couponId);

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

    /** 退款恢复：仅 USED/LOCKED 可改为目标状态。 */
    @Update("update user_coupon set status = #{toStatus}, lock_order_id = null, update_time = now() "
            + "where id = #{id} and status in ('USED','LOCKED') and deleted = 0")
    int restoreAfterRefund(@Param("id") Long id, @Param("toStatus") String toStatus);

    /** 同一订单是否已经锁定了这张券。 */
    @Select("select count(*) from user_coupon where id = #{id} and user_id = #{userId} "
            + "and status in ('LOCKED','USED') and lock_order_id = #{orderId} and deleted = 0")
    int countLockedByOrder(@Param("id") Long id, @Param("userId") Long userId, @Param("orderId") Long orderId);
}
