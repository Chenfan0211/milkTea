package com.wuling.trade;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 交易服务启动类（第 7 期拆分）。
 *
 * 端口 8084，由网关按 /api/v1/app/orders/** 与 /api/v1/admin/trade/** 路由。
 *
 * scanBasePackages 说明：
 *   本服务含 com.wuling.trade（业务）、com.wuling.security（安全公共）、
 *   com.wuling.common（契约/MQ/Redis）三部分包，需放宽扫描范围。
 *
 * @MapperScan 只扫本服务自己的 mapper —— 商品/主体/结算等外部数据
 * 一律通过 port 包下的远程实现访问，不再直连他域的表。
 *
 * @EnableScheduling：订单超时关闭依赖定时/延迟消息机制。
 */
@SpringBootApplication(scanBasePackages = "com.wuling")
@EnableDiscoveryClient
@EnableScheduling
@MapperScan("com.wuling.trade.mapper")
public class TradeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradeServiceApplication.class, args);
    }
}
