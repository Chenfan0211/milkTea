package com.wuling.product.config;

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
 * 商品服务 Spring Security 配置（第 13 期）。
 *
 * <p>外层放行，需登录的接口由 {@link ProductSecurityConfig} 的拦截器精确拦截：
 * 管理端商品维护需登录，小程序菜单/详情为公开商品数据。
 *
 * <p><b>为什么必须显式配置</b>：本服务引入了 spring-boot-starter-security，
 * 若不提供 SecurityFilterChain，Spring Security 的默认策略是「所有请求需认证」，
 * 会把 {@code /internal/**}（供 trade 调用）和 {@code /actuator/health}
 * 一并拦成 401，导致下单校验失败、探活失败。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 内部调用：仅回环可达，不经网关（网关只路由 /api/v1/** 与 /auth/**）
                        .requestMatchers("/internal/**").permitAll()
                        // 健康检查：供探活，不泄露细节（management.endpoint.health.show-details=never）
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // 业务接口外层放行；管理端与小程序登录校验由拦截器/网关完成
                        .requestMatchers("/api/v1/**").permitAll()
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
