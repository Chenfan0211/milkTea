package com.wuling.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 用户真实 IP 透传测试。
 *
 * <p><b>为什么必须测</b>：易宝 {@code aggpay/pre-pay} 的 {@code userIp} 是风控字段。
 * 若透传失效，所有用户会变成同一个地址（网关 IP），风控特征完全失效；
 * 若未剥离外部伪造值，客户端可伪造来源绕过下游 IP 风控与限流。
 */
class ClientIpForwardFilterTest {

    private final ClientIpForwardFilter filter = new ClientIpForwardFilter();

    /** 捕获下游实际收到的请求，便于断言头值。 */
    private ServerWebExchange run(MockServerHttpRequest request) {
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        GatewayFilterChain chain = exchange -> {
            captured.set(exchange);
            return Mono.empty();
        };
        filter.filter(MockServerWebExchange.from(request), chain).block();
        return captured.get();
    }

    @Test
    void forwardedForShouldBeOverwrittenWithPeerAddress() {
        // 无上游代理：用 TCP 对端地址
        ServerWebExchange result = run(MockServerHttpRequest.get("/api/v1/app/orders")
                .remoteAddress(new InetSocketAddress("203.0.113.7", 50000))
                .build());

        HttpHeaders headers = result.getRequest().getHeaders();
        assertEquals("203.0.113.7", headers.getFirst(ClientIpForwardFilter.HEADER_XFF));
        assertEquals("203.0.113.7", headers.getFirst(ClientIpForwardFilter.HEADER_X_REAL_IP));
    }

    @Test
    void forgedForwardedForMustBeDiscarded() {
        // 客户端伪造 XFF，必须被覆盖为真实对端地址
        ServerWebExchange result = run(MockServerHttpRequest.get("/api/v1/app/orders")
                .header(ClientIpForwardFilter.HEADER_XFF, "1.2.3.4")
                .remoteAddress(new InetSocketAddress("203.0.113.7", 50000))
                .build());

        assertEquals("203.0.113.7",
                result.getRequest().getHeaders().getFirst(ClientIpForwardFilter.HEADER_XFF));
    }

    @Test
    void realIpFromNginxShouldWin() {
        // 上游 nginx 写入的 X-Real-IP 可信，优先采用
        ServerWebExchange result = run(MockServerHttpRequest.get("/api/v1/app/orders")
                .header(ClientIpForwardFilter.HEADER_X_REAL_IP, "198.51.100.9")
                .remoteAddress(new InetSocketAddress("10.0.0.5", 50000))
                .build());

        assertEquals("198.51.100.9",
                result.getRequest().getHeaders().getFirst(ClientIpForwardFilter.HEADER_XFF));
    }

    @Test
    void privateAddressInForwardedChainShouldBeSkipped() {
        // 不信任外部 XFF：即便里面混了公网地址，也不能采信。
        // 只有 nginx 的 X-Real-IP 或 TCP 对端才可信。
        ServerWebExchange result = run(MockServerHttpRequest.get("/api/v1/app/orders")
                .header(ClientIpForwardFilter.HEADER_XFF, "10.0.0.5, 203.0.113.7")
                .remoteAddress(new InetSocketAddress("10.0.0.9", 50000))
                .build());

        // 采用对端地址（10.0.0.9），而不是 XFF 里的 203.0.113.7
        assertEquals("10.0.0.9",
                result.getRequest().getHeaders().getFirst(ClientIpForwardFilter.HEADER_XFF));
    }

    @Test
    void realIpShouldOverrideForgedForwardedFor() {
        // nginx 写入的 X-Real-IP 可信，XFF 里的伪造值必须被丢弃
        ServerWebExchange result = run(MockServerHttpRequest.get("/api/v1/app/orders")
                .header(ClientIpForwardFilter.HEADER_X_REAL_IP, "198.51.100.9")
                .header(ClientIpForwardFilter.HEADER_XFF, "1.2.3.4")
                .remoteAddress(new InetSocketAddress("10.0.0.5", 50000))
                .build());

        assertEquals("198.51.100.9",
                result.getRequest().getHeaders().getFirst(ClientIpForwardFilter.HEADER_XFF));
    }

    @Test
    void actuatorPathShouldBeUntouched() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/actuator/health")
                .remoteAddress(new InetSocketAddress("203.0.113.7", 50000))
                .build();
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        GatewayFilterChain chain = exchange -> {
            captured.set(exchange);
            return Mono.empty();
        };
        filter.filter(MockServerWebExchange.from(request), chain).block();

        // 探活不注入，避免无谓开销
        assertNull(captured.get().getRequest().getHeaders()
                .getFirst(ClientIpForwardFilter.HEADER_XFF));
    }

    @Test
    void filterShouldRunBeforeAuthFilter() {
        // 必须早于鉴权：即使请求被拒，日志里也要能看到真实来源
        assertEquals(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10,
                filter.getOrder());
    }
}
