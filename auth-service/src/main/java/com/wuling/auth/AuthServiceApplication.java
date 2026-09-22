package com.wuling.auth;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 认证服务启动类（第 4 期：阶段 B —— 业务迁移完成）。
 *
 * 端口 8081，由网关按 /auth/** 路由。
 *
 * scanBasePackages 说明（重要）：
 *   本服务同时包含 com.wuling.auth（业务）与 com.wuling.common（公共能力副本）。
 *   @SpringBootApplication 默认只扫描启动类所在包及其子包（com.wuling.auth.**），
 *   因此必须显式指定 scanBasePackages = "com.wuling"，否则
 *   com.wuling.common.redis.LoginGuardConfig 等 Bean 不会被注册。
 *
 * @MapperScan 只扫描本服务自己的 mapper：单体 server 与 auth-service 共用同一
 * MySQL 实例，但 Mapper 互不干涉 —— 数据库访问边界随服务收敛。
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.wuling",
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = com.wuling.common.mq.RabbitConfig.class))
@EnableDiscoveryClient
@MapperScan("com.wuling.auth.mapper")
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
