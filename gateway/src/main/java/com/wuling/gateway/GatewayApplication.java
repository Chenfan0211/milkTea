package com.wuling.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * API 网关启动类（第 3 期）。
 *
 * 端口 8080（对外统一入口，与原单体一致，前端无需改动 BASE_URL）。
 *
 * 注意：本模块是 Spring Cloud Gateway（WebFlux/Netty 响应式栈），
 * 禁止引入 spring-boot-starter-web，否则 Servlet 容器与 Netty 冲突。
 */
@SpringBootApplication
@EnableDiscoveryClient
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
