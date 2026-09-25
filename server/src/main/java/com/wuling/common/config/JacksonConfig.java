package com.wuling.common.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 全局日期时间序列化配置。
 *
 * <p>背景：application.yml 中的 spring.jackson.date-format 只对 java.util.Date 生效，
 * 对 LocalDateTime / LocalDate 不生效，导致后端返回 ISO 带 T 格式（2026-09-21T19:57:35）。
 * 这里统一注册 JSR-310 序列化/反序列化器，输出为 yyyy-MM-dd HH:mm:ss 与 yyyy-MM-dd。
 *
 * <p>影响范围：所有直接返回 LocalDateTime / LocalDate 的实体与接口（列表、详情、
 * 审计日志等），一处配置全局生效；已手动 format 成 String 的字段不受影响。
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jsr310Customizer() {
        return builder -> builder
                .serializers(new LocalDateTimeSerializer(DATE_TIME))
                .serializers(new LocalDateSerializer(DATE))
                .deserializers(new LocalDateTimeDeserializer(DATE_TIME))
                .deserializers(new LocalDateDeserializer(DATE));
    }
}
