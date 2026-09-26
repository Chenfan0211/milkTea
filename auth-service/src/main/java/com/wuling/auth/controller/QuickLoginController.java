package com.wuling.auth.controller;

import com.wuling.auth.dto.LoginResponse;
import com.wuling.auth.security.AdminUserDetails;
import com.wuling.auth.security.JwtTokenProvider;
import com.wuling.auth.security.UserDetailsServiceImpl;
import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.audit.AuditLogService;
import com.wuling.common.exception.BusinessException;
import com.wuling.common.security.LoginAttemptGuard;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 运营后台「快捷登录」（一键免密）。
 *
 * <p><b>为什么需要它</b>：登录页原先的 4 个快捷按钮把弱口令（{@code 123456}）
 * 硬编码在前端源码里，口令会随构建产物一起发布出去 —— 任何人打开 dist 都能拿到
 * 4 个后台账号的口令。本接口把「免密」这件事收进服务端：前端只发账号标识，
 * 口令不出现在任何前端产物中。
 *
 * <p><b>安全边界（必读）</b>：免密登录天然是一个后门，因此这里把它收敛到最小面：
 * <ol>
 *   <li><b>白名单</b>：只有 {@link #QUICK_LOGIN_ACCOUNTS} 里登记的 4 个账号可用。
 *       白名单写在服务端，前端传别的标识一律拒绝 —— 避免被用来枚举/登录任意账号，
 *       尤其是「超管唯一且不可改」的另一条规则不能在这里被绕过；</li>
 *   <li><b>状态校验</b>：账号必须存在、未删除且 {@code status = 1}。
 *       停用账号无法通过快捷入口复活；</li>
 *   <li><b>复用登录限流</b>：与 {@code /auth/login} 共用
 *       {@link LoginAttemptGuard} 的「用户名 + IP」双维度计数，
 *       被锁定期间快捷登录一并拒绝；</li>
 *   <li><b>审计留痕</b>：每次快捷登录都写一条 {@code QUICK_LOGIN} 审计，
 *       记录来源 IP。事后可查"谁在什么时候用免密进了后台"；</li>
 *   <li><b>可一键关闭</b>：{@code app.quick-login.enabled=false} 即整体禁用
 *       （返回 403），无需改代码重新发版。正式上线建议关闭。</li>
 * </ol>
 *
 * <p>若后续要改成「后台可维护的快捷账号」，把本类里的白名单换成
 * {@code sys_user} 的查询条件即可，接口契约（入参 key、出参 LoginResponse）保持不变。
 */
@RestController
public class QuickLoginController {

    /**
     * 快捷登录账号白名单：key -> 真实用户名。
     *
     * <p>key 是前端按钮的稳定标识，与 {@code sys_user.username} 解耦 ——
     * 用户名大小写受数据库 collation（utf8mb4_unicode_ci）影响，
     * 直接拿用户名当 key 容易在联调中踩到大小写不一致的坑。
     *
     * <p>与 V21 迁移里的 4 个种子账号一一对应：
     * super（超管）/ operator（运营）/ Finance（财务）/ Audit（审计）。
     */
    private static final Map<String, String> QUICK_LOGIN_ACCOUNTS = Map.of(
            "super", "super",
            "operation", "operator",
            "finance", "Finance",
            "audit", "Audit"
    );

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(QuickLoginController.class);

    private final UserDetailsServiceImpl userDetailsService;
    private final JwtTokenProvider tokenProvider;
    private final LoginAttemptGuard loginGuard;
    private final AuditLogService auditLog;

    @org.springframework.beans.factory.annotation.Value("${app.quick-login.enabled:true}")
    private boolean quickLoginEnabled;

    public QuickLoginController(UserDetailsServiceImpl userDetailsService,
                                JwtTokenProvider tokenProvider,
                                LoginAttemptGuard loginGuard,
                                AuditLogService auditLog) {
        this.userDetailsService = userDetailsService;
        this.tokenProvider = tokenProvider;
        this.loginGuard = loginGuard;
        this.auditLog = auditLog;
    }

    /**
     * 免密登录。
     *
     * <p>返回结构与 {@code /auth/login} 完全一致，前端两条链路共用同一套
     * token 落库与跳转逻辑。
     *
     * @param key 快捷账号标识（super / operation / finance / audit）
     */
    @PostMapping("/auth/quick-login/{key}")
    public Result<LoginResponse> quickLogin(@PathVariable String key,
                                            jakarta.servlet.http.HttpServletRequest httpRequest) {
        String ip = clientIp(httpRequest);
        String normalizedKey = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);

        if (!quickLoginEnabled) {
            throw new BusinessException(ResultCode.FORBIDDEN, "快捷登录已关闭，请使用用户名密码登录");
        }

        String username = QUICK_LOGIN_ACCOUNTS.get(normalizedKey);
        if (username == null) {
            // 不区分「key 不存在」与「key 非法」：避免被用来探测有哪些快捷账号
            auditLog.record("anonymous", "AUTH", "QUICK_LOGIN_REJECT", normalizedKey, "非白名单快捷账号", ip);
            throw new BusinessException(1004, "该快捷登录入口不可用");
        }

        String userKey = "user:" + username.toLowerCase(Locale.ROOT);
        String ipKey = "ip:" + ip;

        long locked = loginGuard.lockedSeconds(userKey, ipKey);
        if (locked > 0) {
            long minutes = (locked + 59) / 60;
            auditLog.record(username, "AUTH", "QUICK_LOGIN_BLOCKED", username,
                    "账号或来源已锁定，剩余 " + locked + " 秒", ip);
            throw new BusinessException(1003, "登录失败次数过多，请 " + minutes + " 分钟后再试");
        }

        AdminUserDetails user;
        try {
            user = (AdminUserDetails) userDetailsService.loadUserByUsername(username);
        } catch (UsernameNotFoundException e) {
            loginGuard.recordFailure(userKey, ipKey);
            auditLog.record(username, "AUTH", "QUICK_LOGIN_FAIL", username, "快捷账号不存在", ip);
            throw new BusinessException(1001, "快捷账号不存在，请使用用户名密码登录");
        }

        if (!user.isEnabled()) {
            auditLog.record(username, "AUTH", "QUICK_LOGIN_FAIL", username, "账号已停用", ip);
            throw new BusinessException(1002, "账号已停用，请联系管理员");
        }

        loginGuard.recordSuccess(userKey, ipKey);
        log.info("quick login success, username={} ip={}", username, ip);
        auditLog.record(user.getUsername(), "AUTH", "QUICK_LOGIN", user.getUsername(),
                "通过登录页快捷入口免密登录", ip);

        LoginResponse data = new LoginResponse(
                tokenProvider.createAccessToken(user.getUserId(), user.getUsername()),
                tokenProvider.createRefreshToken(user.getUserId(), user.getUsername()));
        return Result.ok(data);
    }

    /**
     * 快捷登录是否可用（前端据此决定是否渲染快捷登录区）。
     *
     * <p>放行清单里加了本接口：它只回一个布尔值，不含任何账号信息，
     * 未登录也可安全访问；前端在关闭状态下直接不渲染按钮，避免"点了才被拒"。
     */
    @GetMapping("/auth/quick-login/enabled")
    public Result<Map<String, Object>> enabled() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", quickLoginEnabled);
        data.put("accounts", quickLoginEnabled ? List.copyOf(QUICK_LOGIN_ACCOUNTS.keySet()) : List.of());
        return Result.ok(data);
    }

    /** 取客户端真实 IP（与 AuthController 同口径，便于审计串联）。 */
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