package com.wuling.trade.config;

import com.wuling.common.api.ResultCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import java.nio.charset.StandardCharsets;

/**
 * 交易服务 Spring Security 配置（第 7 期）。
 *
 * <p>与 server / marketing 保持同一鉴权模型：
 * 外层放行 {@code /api/v1/app/**}，需登录的接口由
 * {@link TradeSecurityConfig} 注册的 {@code MiniAppAuthInterceptor} 精确拦截。
 *
 * <p>{@code /internal/**} 也放行 —— 供其他服务调用，仅监听 127.0.0.1
 * 且不经网关暴露。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 内部调用（仅回环可达，不经网关）
                        .requestMatchers("/internal/**").permitAll()
                        // 外层放行；登录校验由 MiniAppAuthInterceptor 完成
                        .requestMatchers("/api/v1/app/**").permitAll()
                        // 管理端接口：JWT 已由网关（GatewayAuthFilter）前置校验，
                        // 本服务不再重复校验（避免为此引入 JWT 过滤器链）。
                        // 生产约束：本服务仅监听 127.0.0.1 且必须经网关访问。
                        .requestMatchers("/api/v1/admin/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(eh -> eh.authenticationEntryPoint((request, response, ex) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.getWriter().write("{\"code\":" + ResultCode.UNAUTHORIZED
                            + ",\"message\":\"未登录或登录已过期\",\"data\":null}");
                }))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
