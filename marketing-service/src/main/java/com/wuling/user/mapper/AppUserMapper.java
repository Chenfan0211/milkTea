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
     * 原子增加储值余额（防丢更新）。
     *
     * @return 1=成功；0=用户不存在
     */
    @Update("update app_user set balance = balance + #{delta}, update_time = now() "
            + "where id = #{userId} and deleted = 0")
    int addBalance(@Param("userId") Long userId, @Param("delta") long delta);
}
