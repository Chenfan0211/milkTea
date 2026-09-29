package com.wuling.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 网关全局限流（自定义，基于 Reactive Redis 固定窗口计数）。
 *
 * <p>为什么不用 Spring Cloud Gateway 内置的 RequestRateLimiter：
 * 内置过滤器依赖 GatewayRedisAutoConfiguration -> RedisRateLimiter -> ReactiveRedisTemplate 的
 * 自动装配链，在 gateway 与 common 的 Redis 依赖叠加下，ReactiveRedisConnectionFactory
 * 的自动配置存在条件竞争，导致 ReactiveRedisTemplate bean 未创建，整条链断裂。
 *
 * <p><b>为什么不声明独立的 ReactiveStringRedisTemplate bean</b>：
 * 若手动 @Bean 一个 ReactiveStringRedisTemplate，会与 Spring Boot 自动配置的
 * reactiveStringRedisTemplate 同时存在（两个同类型 bean），导致
 * GatewayRedisAutoConfiguration#redisRateLimiter 注入歧义而启动失败。
 * 因此这里【不声明 bean】，改为在过滤器内用注入的 ReactiveRedisConnectionFactory
 * 现场构建一个指向独立 db 的模板，既隔离 db，又避免多 bean 冲突。
 *
 * <p><b>db/key 定位（排查 429 的关键）</b>：
 * <ul>
 *   <li>限流数据写入<b>独立 db（默认 db4，可用 {@code RATE_LIMIT_REDIS_DB} 覆盖）</b>，
 *       与业务命名空间（AUTH=db0 / SMS=db1 / CACHE=db2 / BIZ=db3）完全隔离；</li>
 *   <li>key 形如 {@code wuling:ratelimit:{group}:{ip}}，其中 group 为路径分组前缀；</li>
 *   <li>启动与每次 429 判定均打印 db 索引与完整 key，可直接用 redis-cli 定位。</li>
 * </ul>
 */
@Component
class RateLimitGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitGlobalFilter.class);

    private static final long RATE_LIMIT_QPS = 200;
    private static final long WINDOW_SECONDS = 1;
    private static final String KEY_PREFIX = "wuling:ratelimit:";

    private final ReactiveStringRedisTemplate redis;
    private final int database;

    RateLimitGlobalFilter(ReactiveRedisConnectionFactory factory,
                          @Value("${app.rate-limit.redis-db:4}") int database) {
        this.database = database;
        this.redis = buildTemplate(factory, database);
        log.info("[限流] 初始化完成 database={} keyPrefix={} qps={} window={}s",
                database, KEY_PREFIX, RATE_LIMIT_QPS, WINDOW_SECONDS);
    }

    /**
     * 现场构建指向独立 db 的响应式模板（不注册为 bean，避免与自动配置的多 bean 冲突）。
     * 单机/主从模式下显式 SELECT db；cluster 模式仅支持 db0，退化为默认连接（key 前缀已隔离）。
     */
    private static ReactiveStringRedisTemplate buildTemplate(ReactiveRedisConnectionFactory factory, int database) {
        if (factory instanceof LettuceConnectionFactory lettuce) {
            LettuceConnectionFactory clone = new LettuceConnectionFactory(
                    lettuce.getStandaloneConfiguration(), lettuce.getClientConfiguration());
            clone.setDatabase(database);
            clone.setShareNativeConnection(false);
            clone.afterPropertiesSet();
            log.info("[限流] Redis 独立连接已创建 database={}", database);
            return new ReactiveStringRedisTemplate(clone);
        }
        log.warn("[限流] 非 Lettuce 单机连接，限流退化为默认 db0（key 前缀隔离）");
        return new ReactiveStringRedisTemplate(factory);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        String ip = resolveIp(exchange);
        // 按「路径分组 + IP」计数，避免单 IP 高频访问不同接口被 200 QPS 总闸误伤（429 误伤根因之一）
        String group = pathGroup(path);
        String key = KEY_PREFIX + group + ":" + ip;

        return redis.opsForValue()
                .increment(key)
                .flatMap(count -> {
                    if (count != null && count == 1L) {
                        return redis.expire(key, Duration.ofSeconds(WINDOW_SECONDS))
                                .then(Mono.just(count));
                    }
                    return Mono.just(count);
                })
                .flatMap(count -> {
                    long c = count == null ? 0L : count;
                    if (c <= RATE_LIMIT_QPS) {
                        return chain.filter(exchange);
                    }
                    // 429 判定：打印完整上下文，便于定位是「误伤」还是「真超限」
                    log.warn("[限流] 触发 429 database={} key={} ip={} path={} count={} qps={}",
                            database, key, ip, path, c, RATE_LIMIT_QPS);
                    return tooManyRequests(exchange);
                })
                .onErrorResume(e -> {
                    // Redis 不可用时放行（fail-open），避免限流组件自身故障拖垮网关
                    log.warn("[限流] Redis 异常，放行请求 database={} key={} err={}",
                            database, key, e.getMessage());
                    return chain.filter(exchange);
                });
    }

    @Override
    public int getOrder() {
        // 早于鉴权(-50)，晚于日志(-100)：保证日志能记录、限流先于鉴权拦截。
        return -60;
    }

    /** 路径分组：取第一段做粗粒度分组，既隔离不同业务，又避免 key 爆炸。 */
    private static String pathGroup(String path) {
        if (path == null || path.isBlank() || path.equals("/")) {
            return "root";
        }
        String[] seg = path.split("/");
        // seg[0] 恒为空字符串（路径以 / 开头）
        return seg.length > 1 && !seg[1].isBlank() ? seg[1] : "root";
    }

    private static String resolveIp(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        if (exchange.getRequest().getRemoteAddress() != null
                && exchange.getRequest().getRemoteAddress().getAddress() != null) {
            return exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
        }
        return "unknown";
    }

    private static Mono<Void> tooManyRequests(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":429,\"message\":\"请求过于频繁，请稍后重试\",\"data\":null}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}