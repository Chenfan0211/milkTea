package com.wuling.marketing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.marketing.entity.UserCoupon;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

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
}