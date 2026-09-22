package com.wuling.gateway.filter;

import com.wuling.gateway.security.GatewayAuthPolicy;
import com.wuling.gateway.security.JwtVerifier;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 网关统一鉴权过滤器（第 3 期）。
 *
 * <p>职责：
 * <ul>
 *   <li>按路由策略判定是否需要登录（见 {@link GatewayAuthPolicy}）；</li>
 *   <li>需要登录时校验 JWT，并把解析出的 userId 以请求头下发给下游，
 *       下游据此做数据归属校验（纵深防御，网关不是唯一防线）；</li>
 *   <li>校验失败返回统一 JSON（与后端 {@code Result} 格式一致），便于前端统一处理。</li>
 * </ul>
 *
 * <p><b>关于请求头伪造</b>：网关会先剥离外部传入的 {@code X-User-Id} 等内部头，
 * 再写入自己解析的值，防止客户端直接伪造身份头绕过下游校验。
 */
@Component
public class GatewayAuthFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayAuthFilter.class);

    /** 内部头：下游服务读取登录用户 ID */
    public static final String HEADER_USER_ID = "X-User-Id";
    /** 内部头：token 类型（access / mini） */
    public static final String HEADER_TOKEN_TYPE = "X-Token-Type";

    private final GatewayAuthPolicy policy;
    private final JwtVerifier verifier;

    public GatewayAuthFilter(GatewayAuthPolicy policy, JwtVerifier verifier) {
        this.policy = policy;
        this.verifier = verifier;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 无论是否需要鉴权，都先剥离外部伪造的内部头
        ServerHttpRequest sanitized = request.mutate()
                .headers(h -> {
                    h.remove(HEADER_USER_ID);
                    h.remove(HEADER_TOKEN_TYPE);
                })
                .build();

        if (!policy.requiresAuth(path)) {
            return chain.filter(exchange.mutate().request(sanitized).build());
        }

        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            return unauthorized(exchange, 8888, "未登录或登录已过期");
        }

        try {
            Claims claims = verifier.parse(header.substring(7));
            String type = claims.get("type", String.class);
            String userId = claims.getSubject();
            if (userId == null) {
                return unauthorized(exchange, 8888, "无效的令牌");
            }

            ServerHttpRequest withIdentity = sanitized.mutate()
                    .header(HEADER_USER_ID, userId)
                    .header(HEADER_TOKEN_TYPE, type == null ? "" : type)
                    .build();
            return chain.filter(exchange.mutate().request(withIdentity).build());
        } catch (JwtVerifier.ExpiredException e) {
            return unauthorized(exchange, 9999, "登录已过期，请重新登录");
        } catch (Exception e) {
            log.warn("gateway jwt verify failed path={} err={}", path, e.getMessage());
            return unauthorized(exchange, 8888, "无效的令牌");
        }
    }

    /** 返回与后端 Result 结构一致的 JSON（HTTP 401） */
    private Mono<Void> unauthorized(ServerWebExchange exchange, int code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}";
        DataBuffer buffer = response.bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // 早于路由转发，晚于 CORS 处理
        return -50;
    }
}
