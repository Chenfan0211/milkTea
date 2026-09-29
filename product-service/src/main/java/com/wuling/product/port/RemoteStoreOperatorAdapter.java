package com.wuling.product.port;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * {@link StoreOperatorPort} 的远程实现。
 *
 * <p>调用 server 的 {@code /internal/store-operator-check}。
 * {@code /internal/**} 不在网关路由范围内，且各服务仅监听 127.0.0.1，
 * 因此不会经公网暴露。
 *
 * <p>架构与 trade 的 {@code RemoteStoreOperatorAdapter} 完全对齐：
 * 生产走 {@code @LoadBalanced} + Nacos 服务名解析；本地 dev 走字面 IP 直连。
 */
@Component
public class RemoteStoreOperatorAdapter implements StoreOperatorPort {

    private static final Logger log = LoggerFactory.getLogger(RemoteStoreOperatorAdapter.class);

    private final RestClient serverRestClient;

    public RemoteStoreOperatorAdapter(@Qualifier("serverInternalRestClient") RestClient serverRestClient) {
        this.serverRestClient = serverRestClient;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean isStoreOperator(Long userId, Long subjectId) {
        if (userId == null || subjectId == null) {
            return false;
        }
        try {
            Map<String, Object> body = serverRestClient.get()
                    .uri("/internal/store-operator-check?userId={u}&subjectId={s}", userId, subjectId)
                    .retrieve()
                    .body(Map.class);
            boolean allowed = body != null && Boolean.TRUE.equals(body.get("allowed"));
            if (!allowed) {
                log.warn("门店经营者归属校验未通过 userId={} subjectId={}", userId, subjectId);
            }
            return allowed;
        } catch (Exception e) {
            // fail-closed：内网异常时拒绝写操作，避免越权放行
            log.error("门店经营者归属校验失败（按拒绝处理） userId={} subjectId={} err={}",
                    userId, subjectId, e.getMessage());
            return false;
        }
    }

    /** 内部客户端配置：server 主体域（subject 未拆服务）。 */
    @Configuration
    public static class InternalClientConfig {

        /**
         * 负载均衡 builder：生产/测试生效（uri 写服务名，由 Nacos 解析）。
         *
         * <p>本地 dev 的 {@code app.internal.direct-connect=true} 会禁用本 bean，
         * 改用 {@link LocalDevClientConfig} 的直连 builder —— 因为
         * {@code @LoadBalanced} 会把字面 IP 当 serviceId 解析并抛
         * {@code Service Instance cannot be null}。
         */
        @Bean
        @LoadBalanced
        @ConditionalOnProperty(name = "app.internal.direct-connect", havingValue = "false", matchIfMissing = true)
        public RestClient.Builder internalRestClientBuilder() {
            return RestClient.builder();
        }

        /** server 内部客户端（wuling-server，承载 subject / user 等未拆域） */
        @Bean
        public RestClient serverInternalRestClient(RestClient.Builder internalRestClientBuilder,
                                                   @Value("${app.internal.server-base:http://wuling-server}") String base) {
            return build(internalRestClientBuilder, base);
        }

        /** 统一超时：内网调用，超时设置较短，避免拖慢选品列表。 */
        private static RestClient build(RestClient.Builder builder, String baseUrl) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
            factory.setReadTimeout((int) Duration.ofSeconds(3).toMillis());
            return builder.requestFactory(factory).baseUrl(baseUrl).build();
        }
    }

    /** 本地 dev 直连配置：baseUrl 为字面 IP 时不能用 @LoadBalanced。 */
    @Configuration
    @ConditionalOnProperty(name = "app.internal.direct-connect", havingValue = "true")
    public static class LocalDevClientConfig {

        @Bean
        public RestClient.Builder internalRestClientBuilder() {
            return RestClient.builder();
        }
    }
}
