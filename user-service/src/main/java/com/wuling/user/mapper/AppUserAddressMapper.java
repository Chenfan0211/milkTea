package com.wuling.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.user.entity.AppUserAddress;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 收货地址 Mapper。
 *
 * <p>所有按 ID 的读写都把 user_id 写入 SQL 条件，避免先查后写的横向越权窗口。
 * 自定义语句显式保留 deleted = 0，不依赖调用方补条件。
 */
public interface AppUserAddressMapper extends BaseMapper<AppUserAddress> {

    /**
     * 新增地址前锁定用户行，串行化同一用户的「20 条上限」和首条默认地址判断。
     */
    @Select("select id from app_user where id = #{userId} and deleted = 0 for update")
    Long lockUser(@Param("userId") Long userId);

    @Select("select * from app_user_address "
            + "where user_id = #{userId} and deleted = 0 "
            + "order by is_default desc, update_time desc, id desc")
    List<AppUserAddress> selectOwnedList(@Param("userId") Long userId);

    @Select("select * from app_user_address "
            + "where id = #{id} and user_id = #{userId} and deleted = 0 limit 1")
    AppUserAddress selectOwnedById(@Param("id") Long id, @Param("userId") Long userId);

    @Select("select count(*) from app_user_address where user_id = #{userId} and deleted = 0")
    long countOwned(@Param("userId") Long userId);

    @Update("update app_user_address set is_default = 0, update_time = now() "
            + "where user_id = #{userId} and deleted = 0 and is_default = 1")
    int clearOwnedDefaults(@Param("userId") Long userId);

    @Update("update app_user_address set is_default = 1, update_time = now() "
            + "where id = #{id} and user_id = #{userId} and deleted = 0")
    int markOwnedDefault(@Param("id") Long id, @Param("userId") Long userId);

    @Update("update app_user_address set deleted = 1, is_default = 0, update_time = now() "
            + "where id = #{id} and user_id = #{userId} and deleted = 0")
    int logicalDeleteOwned(@Param("id") Long id, @Param("userId") Long userId);

    @Select("select * from app_user_address "
            + "where user_id = #{userId} and deleted = 0 "
            + "order by update_time desc, id desc limit 1")
    AppUserAddress selectMostRecentOwned(@Param("userId") Long userId);
}