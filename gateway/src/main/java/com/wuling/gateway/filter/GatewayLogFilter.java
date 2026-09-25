package com.wuling.gateway.filter;

import com.wuling.common.logging.SensitiveDataMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关请求日志过滤器（WebFlux 版）。
 *
 * <p>为什么要单独写而不是复用 {@link com.wuling.common.logging.RequestLogFilter}：
 * 网关是响应式栈（WebFlux/Netty），Servlet Filter 无法使用（引入会启动失败）。
 *
 * <p>记录内容：入口方法、路径、查询串、调用人（若已解析出 X-User-Id）、
 * 响应状态、端到端耗时。请求体在网关不缓存（避免缓冲大 body 影响吞吐），
 * 参数明细由下游各服务的 RequestLogFilter 负责。
 *
 * <p>安全：查询串默认脱敏（token / 手机号等），仅当
 * {@code app.log.request.full=true} 时原样输出。
 */
@Component
public class GatewayLogFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger("REQUEST");

    private final boolean fullLog;

    public GatewayLogFilter(@Value("${app.log.request.full:false}") boolean fullLog) {
        this.fullLog = fullLog;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 探活不打日志
        if (path.startsWith("/actuator")) {
            return chain.filter(exchange);
        }

        String method = request.getMethod().name();
        String query = mask(request.getURI().getRawQuery());
        long start = System.currentTimeMillis();

        return chain.filter(exchange).doFinally(signal -> {
            long cost = System.currentTimeMillis() - start;
            Object status = exchange.getResponse().getStatusCode() == null
                    ? "-" : exchange.getResponse().getStatusCode().value();
            String userId = firstNonBlank(request.getHeaders().getFirst(GatewayAuthFilter.HEADER_USER_ID),
                    "anonymous");

            StringBuilder sb = new StringBuilder(160);
            sb.append("GW user=").append(userId)
                    .append(" ").append(method).append(" ").append(path);
            if (query != null && !query.isEmpty()) {
                sb.append("?").append(query);
            }
            sb.append(" status=").append(status).append(" cost=").append(cost).append("ms");

            String line = sb.toString();
            int code = 0;
            try {
                code = exchange.getResponse().getStatusCode() == null
                        ? 0 : exchange.getResponse().getStatusCode().value();
            } catch (Exception ignored) {
                // 状态码不可读时按 info 记录
            }
            if (code >= 500) {
                log.error(line);
            } else if (code >= 400) {
                log.warn(line);
            } else {
                log.info(line);
            }
        });
    }

    private String mask(String text) {
        if (text == null) {
            return null;
        }
        return fullLog ? text : SensitiveDataMasker.maskJson(text);
    }

    private String firstNonBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    @Override
    public int getOrder() {
        // 在鉴权过滤器之前：这样日志能记录到被鉴权拦下的请求
        return -100;
    }
}
