package com.wuling.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 透传用户真实 IP 给下游服务。
 *
 * <p><b>为什么需要</b>：易宝 {@code aggpay/pre-pay} 的 {@code userIp} 是风控字段，
 * 必须传<b>付款用户</b>的真实出网 IP，而不是服务器或网关地址。
 * 微服务链路中下游 {@code getRemoteAddr()} 拿到的是网关地址，
 * 因此必须在网关这一跳注入。
 *
 * <p><b>为什么不用 {@code spring.cloud.gateway.x-forwarded} 配置</b>：
 * 那是框架行为，不同 gateway 版本差异大（本项目锁定 4.1.5）。
 * 显式过滤器行为确定、可单测，且能控制「剥离外部伪造值」这一安全细节。
 *
 * <p><b>安全：剥离外部传入值</b>。客户端可以自己塞一个假的
 * {@code X-Forwarded-For}，若不剥离：
 * <ul>
 *   <li>下游基于 IP 的风控（易宝 userIp）会被伪造；</li>
 *   <li>{@code RateLimitGlobalFilter} 的限流 key 也会被绕过。</li>
 * </ul>
 * 因此这里采用「<b>覆盖</b>而非追加」，与 {@link GatewayAuthFilter}
 * 剥离 {@code X-User-Id} 的做法保持一致。
 *
 * <p><b>为什么信任 {@code X-Real-IP}</b>：本项目前面有一层 nginx，
 * 它写入的 {@code X-Real-IP} 来自真实 TCP 连接，比客户端自带的 XFF 可信。
 * 若将来直接暴露网关到公网，应去掉这条信任。
 */
@Component
public class ClientIpForwardFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ClientIpForwardFilter.class);

    /** 标准转发头：逗号分隔的链路，第一个是客户端 */
    public static final String HEADER_XFF = "X-Forwarded-For";
    /** nginx 常用头：单值 */
    public static final String HEADER_X_REAL_IP = "X-Real-IP";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        // 探活与静态资源不需要，减少无谓开销
        String path = request.getURI().getPath();
        if (path.startsWith("/actuator")) {
            return chain.filter(exchange);
        }

        String clientIp = resolveClientIp(request);
        if (!StringUtils.hasText(clientIp)) {
            // 取不到就保持原样：下游有兜底，不因缺 IP 阻断业务
            return chain.filter(exchange);
        }

        // 覆盖而非追加，且先 remove 再 set：
        // 直接用 set 在某些实现下会保留原有多值，导致下游取到伪造的那个。
        ServerHttpRequest mutated = request.mutate()
                .headers(h -> {
                    h.remove(HEADER_XFF);
                    h.remove(HEADER_X_REAL_IP);
                    h.set(HEADER_XFF, clientIp);
                    h.set(HEADER_X_REAL_IP, clientIp);
                })
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    /**
     * 解析客户端真实 IP。
     *
     * <p><b>安全前提</b>：外部传入的 {@code X-Forwarded-For} <b>一律不可信</b>——
     * 客户端可以随意伪造。因此只信任两类来源：
     * <ol>
     *   <li>上游 nginx 写入的 {@code X-Real-IP}（来自真实 TCP 连接）；</li>
     *   <li>网关自己的 TCP 对端地址 {@code getRemoteAddress()}。</li>
     * </ol>
     *
     * <p><b>为什么不解析 XFF</b>：本项目的部署结构是
     * 「客户端 → nginx → 网关」，nginx 已经用 X-Real-IP 传递了真实来源。
     * 若再解析 XFF，等于给伪造值开了后门。
     *
     * <p>若将来直连公网（前面没有 nginx），对端地址就是用户 IP，仍然正确。
     */
    private String resolveClientIp(ServerHttpRequest request) {
        // 1) 上游 nginx 传入（可信，因为它来自真实 TCP 连接）
        String realIp = request.getHeaders().getFirst(HEADER_X_REAL_IP);
        if (StringUtils.hasText(realIp)) {
            String ip = realIp.trim();
            // nginx 通常传单个地址；多值取最后一个（最近一跳写入的）
            if (ip.contains(",")) {
                String[] parts = ip.split(",");
                ip = parts[parts.length - 1].trim();
            }
            if (StringUtils.hasText(ip) && !isPrivateIp(ip)) {
                return ip;
            }
        }

        // 2) 网关自己的 TCP 对端（客户端直连网关时即为用户 IP）
        if (request.getRemoteAddress() == null
                || request.getRemoteAddress().getAddress() == null) {
            return null;
        }
        return request.getRemoteAddress().getAddress().getHostAddress();
    }

    /**
     * 是否内网/回环地址。
     *
     * <p>内网地址不能作为易宝的 userIp：所有用户会变成同一个内网值，
     * 风控特征失效。
     */
    private boolean isPrivateIp(String ip) {
        if (!StringUtils.hasText(ip)) {
            return true;
        }
        if (ip.startsWith("10.") || ip.startsWith("127.") || ip.startsWith("192.168.")) {
            return true;
        }
        // 172.16.0.0 ~ 172.31.255.255
        if (ip.startsWith("172.")) {
            String[] seg = ip.split("\\.");
            if (seg.length > 1) {
                try {
                    int second = Integer.parseInt(seg[1]);
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
        }
        // IPv6 回环与链路本地
        if ("::1".equals(ip) || ip.startsWith("fe80:") || ip.startsWith("fc")
                || ip.startsWith("fd")) {
            return true;
        }
        return false;
    }

    @Override
    public int getOrder() {
        // 排在鉴权之前：即使请求被拒，日志里也能看到真实来源
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
