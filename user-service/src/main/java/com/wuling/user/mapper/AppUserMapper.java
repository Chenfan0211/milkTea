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

    /**
     * 统计某用户邀请注册的人数（即 referrer_id = 该用户的记录数）。
     *
     * <p>「已邀请人数」= 通过该用户分享链接注册、且成功写入了
     * {@code app_user.referrer_id} 的账号数量。这里是权威计数来源，
     * 前端不再硬编码。
     *
     * @param userId 邀请人用户 ID
     * @return 被邀请注册的用户数（不含已删除账号）
     */
    @org.apache.ibatis.annotations.Select(
            "select count(*) from app_user where referrer_id = #{userId} and deleted = 0")
    long countByReferrer(@Param("userId") Long userId);
}
