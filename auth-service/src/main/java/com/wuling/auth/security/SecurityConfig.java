package com.wuling.auth.security;

import com.wuling.common.api.ResultCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

/**
 * 认证服务安全配置（第 4 期：阶段 B）。
 *
 * <p>放行清单：
 * <ul>
 *   <li>{@code /auth/login}、{@code /auth/refreshToken} —— 登录与刷新（本身用于获取凭证）；</li>
 *   <li>{@code /auth/quick-login/**} —— 登录页快捷入口（一键免密）。
 *       它也是发证入口，与 {@code /auth/login} 同级；服务端用白名单把范围锁死在
 *       4 个演示账号上，可通过 {@code app.quick-login.enabled=false} 整体关闭。</li>
 *   <li>{@code /actuator/health} —— 探活；</li>
 *   <li>{@code /internal/service-info} —— 服务自述（不含敏感信息）。</li>
 * </ul>
 * 其余接口一律需要 JWT。
 *
 * <p>登录接口本身无鉴权是正常的（它就是发证入口），
 * 爆破风险由 {@code LoginAttemptGuard}（5 次失败锁定 15 分钟）与审计日志覆盖。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/login", "/auth/refreshToken").permitAll()
                        // 快捷登录（一键免密）：本身就是发证入口，与 /auth/login 同级；
                        // 风险由服务端白名单 + LoginAttemptGuard + 审计日志覆盖，
                        // 详见 QuickLoginController 的「安全边界」。
                        .requestMatchers("/auth/quick-login/enabled").permitAll()
                        .requestMatchers("/auth/quick-login/*").permitAll()
                        .requestMatchers("/actuator/health", "/internal/service-info").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(eh -> eh.authenticationEntryPoint((request, response, ex) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.getWriter().write("{\"code\":" + ResultCode.UNAUTHORIZED
                            + ",\"message\":\"未登录或登录已过期\",\"data\":null}");
                }))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
