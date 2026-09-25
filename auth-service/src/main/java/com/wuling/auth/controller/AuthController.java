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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthController.class);

    private final UserDetailsServiceImpl userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final com.wuling.common.security.LoginAttemptGuard loginGuard;
    private final com.wuling.common.audit.AuditLogService auditLog;

    public AuthController(UserDetailsServiceImpl userDetailsService,
                          PasswordEncoder passwordEncoder,
                          JwtTokenProvider tokenProvider,
                          com.wuling.common.security.LoginAttemptGuard loginGuard,
                          com.wuling.common.audit.AuditLogService auditLog) {
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.loginGuard = loginGuard;
        this.auditLog = auditLog;
    }

    /**
     * 管理端登录。
     *
     * P2 安全加固：按「用户名 + 来源 IP」双维度限制连续失败次数，
     * 达到阈值锁定 {com.wuling.common.security.LoginAttemptGuard#MAX_FAILURES} 次对应的时长，
     * 防止口令暴力破解。
     *
     * 为什么用双维度：只按用户名锁定会让攻击者故意失败来锁死他人账号（DoS）；
     * 加上 IP 维度后，同一来源的撞库也会被拦截。
     */
    @PostMapping("/auth/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                       jakarta.servlet.http.HttpServletRequest httpRequest) {
        String username = request.getUserName();
        String ip = clientIp(httpRequest);
        String userKey = "user:" + (username == null ? "-" : username.toLowerCase());
        String ipKey = "ip:" + ip;

        // 1) 先看是否已锁定（不再校验口令，避免继续消耗资源）
        long locked = loginGuard.lockedSeconds(userKey, ipKey);
        if (locked > 0) {
            long minutes = (locked + 59) / 60;
            log.warn("login blocked by lock, username={} ip={} remainSec={}", username, ip, locked);
            auditLog.record(username, "AUTH", "LOGIN_BLOCKED", username,
                    "账号或来源已锁定，剩余 " + locked + " 秒", ip);
            throw new BusinessException(1003, "登录失败次数过多，请 " + minutes + " 分钟后再试");
        }

        AdminUserDetails user;
        try {
            user = (AdminUserDetails) userDetailsService.loadUserByUsername(request.getUserName());
        } catch (UsernameNotFoundException e) {
            onLoginFailure(userKey, ipKey, username, ip);
            throw new BusinessException(1001, "用户名或密码错误");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            onLoginFailure(userKey, ipKey, username, ip);
            throw new BusinessException(1001, "用户名或密码错误");
        }
        if (!user.isEnabled()) {
            throw new BusinessException(1002, "账号已停用");
        }
        // 登录成功：清空失败计数
        loginGuard.recordSuccess(userKey, ipKey);
        LoginResponse data = new LoginResponse(
                tokenProvider.createAccessToken(user.getUserId(), user.getUsername()),
                tokenProvider.createRefreshToken(user.getUserId(), user.getUsername()));
        return Result.ok(data);
    }

    @GetMapping("/auth/getUserInfo")
    public Result<UserInfoResponse> getUserInfo(@AuthenticationPrincipal AdminUserDetails user) {
        UserInfoResponse data = new UserInfoResponse(
                String.valueOf(user.getUserId()), user.getUsername(), user.getRoles(), List.of(),
                user.isSuperAccount());
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

    /** 记录一次登录失败；达到阈值时打印告警 */
    private void onLoginFailure(String userKey, String ipKey, String username, String ip) {
        boolean locked = loginGuard.recordFailure(userKey, ipKey);
        if (locked) {
            log.warn("login locked after too many failures, username={} ip={}", username, ip);
        }
        // 留痕：便于事后排查暴力破解（不记录口令本身）
        auditLog.record(username, "AUTH", locked ? "LOGIN_FAIL_LOCKED" : "LOGIN_FAIL",
                username, "用户名或密码错误", ip);
    }

    /**
     * 取客户端真实 IP。
     *
     * 生产部署在 Nginx 之后，remoteAddr 恒为网关地址，
     * 故优先读 X-Forwarded-For 的第一段（最靠近客户端的来源）。
     */
    private String clientIp(jakarta.servlet.http.HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
