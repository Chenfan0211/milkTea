package com.wuling.marketing.config;

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
 * 营销服务 Spring Security 配置（第 5 期）。
 *
 * <p>与单体 server 保持同一鉴权模型（重要）：
 * <ul>
 *   <li><b>外层放行</b> {@code /api/v1/app/**} —— 该前缀下既有公开接口（优惠券模板、
 *       储值套餐、积分商品、会员等级），也有需登录接口（我的券、充值、签到、兑换）；</li>
 *   <li><b>精确拦截</b>由 {@link MarketingSecurityConfig} 注册的
 *       {@code MiniAppAuthInterceptor} 按路径清单负责。</li>
 * </ul>
 *
 * <p>为什么不在 Security 层用 requestMatchers 精确声明：
 * 与单体保持一致可避免同一接口在拆分前后出现鉴权差异；
 * 两处清单需同步维护（已在改造方案中登记该约束）。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 外层放行；真正的登录校验由 MiniAppAuthInterceptor 完成
                        .requestMatchers("/api/v1/app/**").permitAll()
                        // 管理端接口：JWT 已由网关（GatewayAuthFilter）前置校验，
                        // 本服务不再重复校验。生产约束：仅监听 127.0.0.1 且必须经网关访问。
                        .requestMatchers("/api/v1/admin/**").permitAll()
                        // 内部接口：供 trade-service 支付回调链路调用（储值订单反查与入账）。
                        // 不经网关暴露，且服务仅监听内网/本机，故放行。
                        .requestMatchers("/internal/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
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
