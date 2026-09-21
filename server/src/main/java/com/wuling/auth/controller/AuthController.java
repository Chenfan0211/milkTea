package com.wuling.auth.controller;

import com.wuling.auth.dto.LoginRequest;
import com.wuling.auth.dto.LoginResponse;
import com.wuling.auth.dto.RefreshRequest;
import com.wuling.auth.dto.UserInfoResponse;
import com.wuling.auth.security.AdminUserDetails;
import com.wuling.auth.security.JwtTokenProvider;
import com.wuling.auth.security.UserDetailsServiceImpl;
import com.wuling.common.api.Result;
import com.wuling.common.exception.BusinessException;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class AuthController {

    private final UserDetailsServiceImpl userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;

    public AuthController(UserDetailsServiceImpl userDetailsService,
                          PasswordEncoder passwordEncoder,
                          JwtTokenProvider tokenProvider) {
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
    }

    @PostMapping("/auth/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        AdminUserDetails user;
        try {
            user = (AdminUserDetails) userDetailsService.loadUserByUsername(request.getUserName());
        } catch (UsernameNotFoundException e) {
            throw new BusinessException(1001, "用户名或密码错误");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(1001, "用户名或密码错误");
        }
        if (!user.isEnabled()) {
            throw new BusinessException(1002, "账号已停用");
        }
        LoginResponse data = new LoginResponse(
                tokenProvider.createAccessToken(user.getUserId(), user.getUsername()),
                tokenProvider.createRefreshToken(user.getUserId(), user.getUsername()));
        return Result.ok(data);
    }

    @GetMapping("/auth/getUserInfo")
    public Result<UserInfoResponse> getUserInfo(@AuthenticationPrincipal AdminUserDetails user) {
        UserInfoResponse data = new UserInfoResponse(
                String.valueOf(user.getUserId()), user.getUsername(), user.getRoles(), List.of());
        return Result.ok(data);
    }

    @PostMapping("/auth/refreshToken")
    public Result<LoginResponse> refreshToken(@Valid @RequestBody RefreshRequest request) {
        Claims claims;
        try {
            claims = tokenProvider.parse(request.getRefreshToken());
        } catch (Exception e) {
            throw new BusinessException(8888, "无效的刷新令牌");
        }
        if (!JwtTokenProvider.TYPE_REFRESH.equals(claims.get("type", String.class))) {
            throw new BusinessException(8888, "无效的刷新令牌");
        }
        Long userId = Long.valueOf(claims.getSubject());
        String username = claims.get("username", String.class);
        LoginResponse data = new LoginResponse(
                tokenProvider.createAccessToken(userId, username),
                tokenProvider.createRefreshToken(userId, username));
        return Result.ok(data);
    }
}
