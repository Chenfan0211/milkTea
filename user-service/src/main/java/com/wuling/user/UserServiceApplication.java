package com.wuling.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 用户服务启动类（第 11 期拆分）。
 *
 * 端口 8085，由网关按 /api/v1/app/auth/** 路由。
 *
 * scanBasePackages：本服务含 com.wuling.user（业务）与 com.wuling.common /
 * com.wuling.security（公共能力），需放宽扫描范围。
 *
 * @MapperScan：只扫本域 mapper。marketing-service 有自己的 AppUser 副本
 * （共享表模式），两者互不影响。
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.wuling",
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = com.wuling.common.mq.RabbitConfig.class))
@EnableDiscoveryClient
@MapperScan("com.wuling.user.mapper")
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
