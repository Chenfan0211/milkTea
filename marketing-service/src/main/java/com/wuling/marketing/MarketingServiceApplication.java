package com.wuling.marketing;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 营销服务启动类（第 5 期拆分）。
 *
 * 端口 8083，由网关按营销相关前缀路由。
 *
 * scanBasePackages 说明：
 *   本服务同时包含 com.wuling.marketing（业务）、com.wuling.security（安全公共）、
 *   com.wuling.common（API 契约与异常处理）、com.wuling.user（共享的 AppUser）。
 *   @SpringBootApplication 默认只扫描启动类所在包，必须显式放宽。
 *
 * @MapperScan 覆盖业务 mapper 与共享的 AppUserMapper。
 */
@SpringBootApplication(scanBasePackages = "com.wuling")
@EnableDiscoveryClient
@EnableScheduling
@MapperScan({"com.wuling.marketing.mapper", "com.wuling.user.mapper"})
public class MarketingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketingServiceApplication.class, args);
    }
}
