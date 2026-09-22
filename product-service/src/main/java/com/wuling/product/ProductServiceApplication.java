package com.wuling.product;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * 商品服务启动类（第 13 期拆分）。
 *
 * <p>端口 8091，由网关路由：
 * <ul>
 *   <li>{@code /api/v1/admin/product/**} —— 管理端商品</li>
 *   <li>{@code /api/v1/app/menu}、{@code /api/v1/app/products/**} —— 小程序菜单</li>
 * </ul>
 *
 * <p>本服务<b>无 MQ 需求</b>，故显式排除 {@code RabbitConfig}：
 * 它位于 wuling-common，其 @Bean 方法签名引用了 AMQP 类型，
 * 即使加了 @ConditionalOnClass，Spring 在内省方法签名时仍会
 * NoClassDefFoundError（第 11 期已踩过此坑）。
 *
 * <p><b>subject 依赖</b>：本域通过 {@code SubjectQueryPort} 读取主体信息。
 * 若将 subject 拆为独立服务，替换 remote 实现即可，业务代码不变。
 * 当前 subject 未拆（第 12 期决策），port 直接读写共享库的 biz_subject 表。
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.wuling",
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = com.wuling.common.mq.RabbitConfig.class))
@EnableDiscoveryClient
@MapperScan({"com.wuling.product.mapper", "com.wuling.product.subject.mapper"})
public class ProductServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductServiceApplication.class, args);
    }
}