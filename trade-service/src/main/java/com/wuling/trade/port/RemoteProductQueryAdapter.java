package com.wuling.trade.port;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * {@link ProductQueryPort} 的远程实现（第 7 期新增，第 13 期改造）。
 *
 * <p>第 13 期 product 拆为独立服务前，本实现调用 server 的
 * {@code /internal/product*}；拆分后商品接口已迁至 product-service，
 * 故改为按域选择目标服务：商品/上架走 {@code wuling-product-service}，
 * 主体名仍走 {@code wuling-server}（subject 未拆）。
 *
 * <p>调用方式：{@code RestClient} + {@code @LoadBalanced}，
 * 通过 Nacos 服务名解析地址，不写死 IP。
 *
 * <p><b>内网约束</b>：{@code /internal/**} 不在网关路由范围内，且各服务仅监听
 * 127.0.0.1，因此这些接口不会经公网暴露。
 *
 * <p><b>失败语义</b>：查询失败时返回 null / false，由调用方按业务规则处理
 * （如抛「商品不存在」）。刻意不吞异常后返回「成功」，
 * 避免把下游故障伪装成业务正常。
 */
@Component
public class RemoteProductQueryAdapter implements ProductQueryPort {

    private static final Logger log = LoggerFactory.getLogger(RemoteProductQueryAdapter.class);

    /** 商品域内部客户端（base-url = wuling-product-service） */
    private final RestClient productRestClient;
    /** 主体域内部客户端（base-url = wuling-server，subject 未拆） */
    private final RestClient serverRestClient;

    public RemoteProductQueryAdapter(
            @Qualifier("productInternalRestClient") RestClient productRestClient,
            @Qualifier("serverInternalRestClient") RestClient serverRestClient) {
        this.productRestClient = productRestClient;
        this.serverRestClient = serverRestClient;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ProductView findProduct(String productId) {
        try {
            Map<String, Object> body = productRestClient.get()
                    .uri("/internal/product?productId={id}", productId)
                    .retrieve()
                    .body(Map.class);
            if (body == null || body.get("productId") == null) {
                return null;
            }
            ProductView view = new ProductView();
            view.setId(asLong(body.get("id")));
            view.setProductId((String) body.get("productId"));
            view.setName((String) body.get("name"));
            view.setPrice(asLong(body.get("price")));
            view.setOriginalPrice(asLong(body.get("originalPrice")));
            view.setImage(body.get("image") == null ? null : String.valueOf(body.get("image")));
            view.setOnSale(asInt(body.get("onSale")));
            view.setSupplierSubjectId(asLong(body.get("supplierSubjectId")));
            view.setPlatformCommission(asLong(body.get("platformCommission")));
            view.setCostPrice(asLong(body.get("costPrice")));
            return view;
        } catch (Exception e) {
            log.error("远程查询商品失败 productId={} err={}", productId, e.getMessage());
            return null;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean isProductInStore(Long productId, Long storeSubjectId) {
        try {
            Map<String, Object> body = productRestClient.get()
                    .uri("/internal/product-in-store?productId={p}&storeSubjectId={s}", productId, storeSubjectId)
                    .retrieve()
                    .body(Map.class);
            return body != null && Boolean.TRUE.equals(body.get("inStore"));
        } catch (Exception e) {
            log.error("远程查询上架状态失败 productId={} store={} err={}", productId, storeSubjectId, e.getMessage());
            return false;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public String findSubjectName(Long subjectId) {
        if (subjectId == null) {
            return null;
        }
        try {
            Map<String, Object> body = serverRestClient.get()
                    .uri("/internal/subject-name?subjectId={id}", subjectId)
                    .retrieve()
                    .body(Map.class);
            return body == null ? null : (String) body.get("name");
        } catch (Exception e) {
            log.error("远程查询主体名失败 subjectId={} err={}", subjectId, e.getMessage());
            return null;
        }
    }

    private Long asLong(Object v) {
        return v == null ? null : Long.valueOf(String.valueOf(v));
    }

    private Integer asInt(Object v) {
        return v == null ? null : Integer.valueOf(String.valueOf(v));
    }

    /**
     * 内部调用专用的 RestClient 配置（第 7 期新增，第 13 期扩展为双目标）。
     *
     * <p>第 13 期拆出 product-service 后，内部调用需按域分流：
     * <ul>
     *   <li>{@code productInternalRestClient} → wuling-product-service（商品/上架）</li>
     *   <li>{@code serverInternalRestClient}  → wuling-server（主体名等未拆域）</li>
     * </ul>
     * 两个 bean 均基于同一个 @LoadBalanced builder，但 baseUrl 不同，
     * 由 Nacos 服务名解析，不写死 IP。
     */
    @Configuration
    public static class InternalClientConfig {

        /**
         * 加载均衡的 RestClient builder：uri 写服务名，
         * 由 LoadBalancer 解析为 Nacos 中的实际实例（生产用法）。
         *
         * <p><b>仅生产/测试生效</b>：本地 dev 用 {@code http://127.0.0.1:port} 直连，
         * 而 @LoadBalanced 会把 host 当作 serviceId 去 Nacos 解析，
         * 对字面 IP 报 {@code Service Instance cannot be null}。
         * 故本地用直连 builder（见 {@link LocalDevClientConfig}）。
         */
        @Bean
        @LoadBalanced
        @ConditionalOnProperty(name = "app.internal.direct-connect", havingValue = "false", matchIfMissing = true)
        public RestClient.Builder internalRestClientBuilder() {
            return RestClient.builder();
        }

        /** 商品域内部客户端（wuling-product-service） */
        @Bean
        public RestClient productInternalRestClient(RestClient.Builder internalRestClientBuilder,
                                                    @Value("${app.internal.product-base:http://wuling-product-service}") String base) {
            return build(internalRestClientBuilder, base);
        }

        /** server 内部客户端（wuling-server，承载 subject / finance 等未拆域） */
        @Bean
        public RestClient serverInternalRestClient(RestClient.Builder internalRestClientBuilder,
                                                   @Value("${app.internal.server-base:http://wuling-server}") String base) {
            return build(internalRestClientBuilder, base);
        }

        /**
         * 用户域内部客户端（wuling-user-service）。
         *
         * <p>第 14 期支付接入新增：微信支付 JSAPI 下单需 openid，
         * 由用户服务提供 {@code /internal/users/{id}/openid}。
         * 用户域已于第 11 期拆为独立服务，故单独一个 base。
         */
        @Bean
        public RestClient userInternalRestClient(RestClient.Builder internalRestClientBuilder,
                                                 @Value("${app.internal.user-base:http://wuling-user-service}") String base) {
            return build(internalRestClientBuilder, base);
        }
        /**
         * 营销域内部客户端（wuling-marketing-service）。
         *
         * <p>储值订单查询/入账走 marketing-service 的 {@code /internal/stored-value-orders/**}，
         * 涉及资金，由 {@link com.wuling.trade.pay.storedvalue.RemoteStoredValueOrderAdapter} 使用。
         */
        @Bean
        public RestClient marketingInternalRestClient(RestClient.Builder internalRestClientBuilder,
                                                      @Value("${app.internal.marketing-base:http://wuling-marketing-service}") String base) {
            return build(internalRestClientBuilder, base);
        }


        /**
         * 统一超时：内网调用，超时设置得较短，避免拖慢下单主链路。
         *
         * <p>本地 dev（{@code app.internal.direct-connect=true}）时
         * baseUrl 为字面 IP，直接把 host 原样用于请求。
         */
        private static RestClient build(RestClient.Builder builder, String baseUrl) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
            factory.setReadTimeout((int) Duration.ofSeconds(3).toMillis());
            return builder.requestFactory(factory).baseUrl(baseUrl).build();
        }
    }

    /**
     * 本地 dev 直连配置（第 13 期）。
     *
     * <p>本地开发时 Nacos 注册被禁用（application-dev.yml 的
     * {@code spring.cloud.nacos.discovery.enabled=false}），
     * {@code app.internal.*-base} 写的是 {@code http://127.0.0.1:port}。
     * 这种字面 IP 无法被 @LoadBalanced 解析，会抛
     * {@code Service Instance cannot be null}。
     *
     * <p>因此本地改用不带 @LoadBalanced 的 builder，
     * 由 RestClient 直接按字面 IP 请求。
     *
     * <p>生产环境不激活本类（{@code app.internal.direct-connect} 缺省为 false），
     * 仍走 @LoadBalanced + Nacos 服务发现。
     */
    @Configuration
    @ConditionalOnProperty(name = "app.internal.direct-connect", havingValue = "true")
    public static class LocalDevClientConfig {

        @Bean
        public RestClient.Builder internalRestClientBuilder() {
            return RestClient.builder();
        }
    }
}
