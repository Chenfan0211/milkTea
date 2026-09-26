package com.wuling.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.user.entity.AppUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface AppUserMapper extends BaseMapper<AppUser> {

    /**
     * 原子增减时光币（防丢更新）。
     * 条件 #{amount} >= 0 与 points + #{amount} >= 0 保证不会扣成负数，
     * 并发下由行锁串行，避免「读-改-写」丢失更新。
     *
     * @param delta 增量，正数增加、负数扣减
     * @return 1=成功；0=余额不足（扣减时）
     */
    @Update("update app_user set points = points + #{delta}, update_time = now() "
            + "where id = #{userId} and deleted = 0 and points + #{delta} >= 0")
    int addPoints(@Param("userId") Long userId, @Param("delta") long delta);

    /**
     * 原子增减储值余额（防丢更新）。
     *
     * <p><b>为什么条件里必须带 {@code balance + delta >= 0}</b>：
     * 扣款（余额支付 / 退款回冲）与加款（充值 / 取消订单退回）共用本方法。
     * 若只判断 {@code id} 存在，并发扣款会「读-改-写」丢失更新，
     * 甚至把余额扣成<b>负数</b> —— 等于凭空透支平台资金。
     * 加上非负条件后，扣到不足时返回 0，由调用方按「余额不足」处理
     * （而不是当成系统错误）。
     *
     * @param delta 增量：正数加款（充值 / 退款退回）、负数扣款（余额支付）
     * @return 1=成功；0=用户不存在或（扣款时）余额不足
     */
    @Update("update app_user set balance = balance + #{delta}, update_time = now() "
            + "where id = #{userId} and deleted = 0 and balance + #{delta} >= 0")
    int addBalance(@Param("userId") Long userId, @Param("delta") long delta);
}
