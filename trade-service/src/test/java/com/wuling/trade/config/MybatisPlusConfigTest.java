package com.wuling.trade.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MybatisPlusConfigTest {

    @Test
    void registersMysqlPaginationInterceptorWithMaxLimit500() throws Exception {
        Class<?> configClass = Class.forName("com.wuling.trade.config.MybatisPlusConfig");

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(configClass);
            context.refresh();

            MybatisPlusInterceptor interceptor = context.getBean(MybatisPlusInterceptor.class);
            PaginationInnerInterceptor pagination = interceptor.getInterceptors().stream()
                    .filter(PaginationInnerInterceptor.class::isInstance)
                    .map(PaginationInnerInterceptor.class::cast)
                    .findFirst()
                    .orElse(null);

            assertNotNull(pagination, "trade-service 必须注册分页拦截器");
            assertEquals(DbType.MYSQL, pagination.getDbType());
            assertEquals(500L, pagination.getMaxLimit());
        }
    }
}