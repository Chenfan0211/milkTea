package com.wuling.product.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 商品服务 MyBatis-Plus 配置。
 *
 * <p><b>修复记录（2026-09-23）</b>：此前本服务缺少分页插件，
 * 导致 {@code productMapper.selectPage(...)} 的 {@code getTotal()} 恒为 0
 * （SQL 未改写为 count 查询），管理端商品列表的分页总数始终显示 0。
 * 现补上 {@link PaginationInnerInterceptor}。
 *
 * <p>与 server 的 {@code MybatisPlusConfig} 保持一致：MySQL 方言 + 单页上限 500。
 * 上限用于防止前端传入超大 size 拖垮数据库。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(500L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
