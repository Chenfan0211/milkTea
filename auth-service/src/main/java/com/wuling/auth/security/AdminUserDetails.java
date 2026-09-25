package com.wuling.auth.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

public class AdminUserDetails implements UserDetails {

    private final Long userId;
    private final String username;
    private final String password;
    private final List<String> roles;
    private final boolean enabled;
    private final boolean superAccount;

    /**
     * @param superAccount 是否为超级管理员账号（sys_user.is_super = 1）。
     *                     由 sys_user 列直接判定，而不是「是否绑定了 R_SUPER」——
     *                     前者是持久化的权威标记，后者在迁移期间可能短暂不一致。
     */
    public AdminUserDetails(Long userId, String username, String password, List<String> roles,
                            boolean enabled, boolean superAccount) {
        this.userId = userId;
        this.username = username;
        this.password = password;
        this.roles = roles == null ? List.of() : roles;
        this.enabled = enabled;
        this.superAccount = superAccount;
    }

    public Long getUserId() {
        return userId;
    }

    public List<String> getRoles() {
        return roles;
    }

    public boolean isSuperAccount() {
        return superAccount;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}

