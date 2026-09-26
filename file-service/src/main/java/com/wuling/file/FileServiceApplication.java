package com.wuling.file;

import com.wuling.security.AdminAuthInterceptor;
import com.wuling.security.AdminJwtVerifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Import;

/**
 * 文件服务启动类。
 *
 * <p>安全组件位于 {@code com.wuling.security}，不在启动类的默认扫描包下，
 * 因此显式导入管理端 JWT 校验器和拦截器。
 */
@SpringBootApplication
@EnableDiscoveryClient
@Import({AdminJwtVerifier.class, AdminAuthInterceptor.class})
public class FileServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileServiceApplication.class, args);
    }
}