package com.wuling.auth.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.auth.entity.SysUser;
import com.wuling.auth.mapper.SysUserMapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final SysUserMapper sysUserMapper;

    public UserDetailsServiceImpl(SysUserMapper sysUserMapper) {
        this.sysUserMapper = sysUserMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在");
        }
        List<String> roles = sysUserMapper.selectRoleCodes(user.getId());
        boolean enabled = user.getStatus() != null && user.getStatus() == 1;
        boolean superAccount = user.getIsSuper() != null && user.getIsSuper() == 1;
        return new AdminUserDetails(user.getId(), user.getUsername(), user.getPassword(), roles,
                enabled, superAccount);
    }
}
