package com.wuling.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.auth.entity.SysUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("select r.code from sys_role r join sys_user_role ur on r.id = ur.role_id "
            + "where ur.user_id = #{userId} and r.deleted = 0 and ur.deleted = 0")
    List<String> selectRoleCodes(@Param("userId") Long userId);
}
