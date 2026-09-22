package com.wuling.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 内部服务调用配置（第 7 期）。
 *
 * <p>供 server 侧调用其他服务（如 trade-service）的 {@code /internal/**} 接口。
 *
 * <p>为什么单独建 RestClient 而不是用已有 WebClient：
 * 内部调用是同步短请求，RestClient 更直观且无需响应式栈。
 *
 * <p>{@code @LoadBalanced}：uri 写服务名（如 wuling-trade-service），
 * 由 LoadBalancer 经 Nacos 解析实例，不写死 IP。
 */
@Configuration
public class InternalClientConfig {

    @Bean
    @LoadBalanced
    public RestClient.Builder internalRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public RestClient internalRestClient(RestClient.Builder internalRestClientBuilder,
                                         @Value("${app.internal.base-url:http://wuling-trade-service}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(3).toMillis());
        return internalRestClientBuilder.requestFactory(factory).baseUrl(baseUrl).build();
    }
}
