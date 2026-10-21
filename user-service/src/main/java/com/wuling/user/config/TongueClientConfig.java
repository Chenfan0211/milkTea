package com.wuling.user.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 舌象第三方与 file-service 内部 RestClient 配置。
 *
 * <p>第三方检测/报告使用独立工厂，确保连接 10s、detection 读取 60s、report 读取 30s；
 * file-service 使用 @LoadBalanced Builder，通过服务名经 Nacos 解析实例。
 */
@Configuration
public class TongueClientConfig {

    @Bean("tongueDetectionRestClient")
    public RestClient tongueDetectionRestClient(TongueProperties properties) {
        return RestClient.builder()
                .requestFactory(factory(properties.getConnectTimeout(), properties.getDetectionReadTimeout()))
                .baseUrl(properties.getBaseUrl())
                .build();
    }

    @Bean("tongueReportRestClient")
    public RestClient tongueReportRestClient(TongueProperties properties) {
        return RestClient.builder()
                .requestFactory(factory(properties.getConnectTimeout(), properties.getReportReadTimeout()))
                .baseUrl(properties.getBaseUrl())
                .build();
    }

    /** 生产/测试：通过 Nacos 服务名负载均衡。 */
    @Bean("tongueFileRestClientBuilder")
    @LoadBalanced
    @ConditionalOnProperty(name = "app.internal.direct-connect", havingValue = "false", matchIfMissing = true)
    public RestClient.Builder tongueFileRestClientBuilder() {
        return RestClient.builder();
    }

    /** 本地 dev：Nacos 关闭时按 127.0.0.1 直连 file-service。 */
    @Bean("tongueFileRestClientBuilder")
    @ConditionalOnProperty(name = "app.internal.direct-connect", havingValue = "true")
    public RestClient.Builder tongueFileDirectRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean("tongueFileRestClient")
    public RestClient tongueFileRestClient(
            @Qualifier("tongueFileRestClientBuilder") RestClient.Builder builder,
            TongueProperties properties) {
        return builder
                .requestFactory(factory(Duration.ofSeconds(10), Duration.ofSeconds(30)))
                .baseUrl(properties.getFileBaseUrl())
                .build();
    }

    /** 生产/测试：通过 Nacos 服务名负载均衡访问 product-service。 */
    @Bean("productCardRestClientBuilder")
    @LoadBalanced
    @ConditionalOnProperty(name = "app.internal.direct-connect", havingValue = "false", matchIfMissing = true)
    public RestClient.Builder productCardRestClientBuilder() {
        return RestClient.builder();
    }

    /** 本地 dev：Nacos 关闭时按 127.0.0.1 直连 product-service。 */
    @Bean("productCardRestClientBuilder")
    @ConditionalOnProperty(name = "app.internal.direct-connect", havingValue = "true")
    public RestClient.Builder productCardDirectRestClientBuilder() {
        return RestClient.builder();
    }

    /**
     * 商城商品卡内部客户端。
     *
     * <p>超时收紧到「连接 2s / 读取 3s」：舌象报告是主流程，
     * 商品卡只是增强信息，超时必须快速降级而不是拖慢报告返回。
     */
    @Bean("productCardRestClient")
    public RestClient productCardRestClient(
            @Qualifier("productCardRestClientBuilder") RestClient.Builder builder,
            @Value("${app.internal.product-base:http://wuling-product-service}") String baseUrl) {
        return builder
                .requestFactory(factory(Duration.ofSeconds(2), Duration.ofSeconds(3)))
                .baseUrl(baseUrl)
                .build();
    }

    private static SimpleClientHttpRequestFactory factory(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.toIntExact(connectTimeout.toMillis()));
        factory.setReadTimeout(Math.toIntExact(readTimeout.toMillis()));
        return factory;
    }
}
