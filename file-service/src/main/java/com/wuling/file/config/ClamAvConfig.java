package com.wuling.file.config;

import com.wuling.file.security.ClamAvScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ClamAV 病毒扫描器装配。
 *
 * <p>仅在 {@code app.clamav.enabled=true} 时装配 {@link ClamAvScanner}；
 * 未启用时 bean 不存在，{@code ImageStorageService} 的 scanner 依赖为 null，
 * 上传流程跳过扫描（本地开发/测试未部署 clamd 时的兼容行为）。
 */
@Configuration
public class ClamAvConfig {

    private static final Logger log = LoggerFactory.getLogger(ClamAvConfig.class);

    @Bean
    @ConditionalOnProperty(name = "app.clamav.enabled", havingValue = "true")
    public ClamAvScanner clamAvScanner(
            @Value("${app.clamav.host:127.0.0.1}") String host,
            @Value("${app.clamav.port:3310}") int port,
            @Value("${app.clamav.timeout-ms:5000}") int timeoutMillis,
            @Value("${app.clamav.chunk-size:4096}") int chunkSize) {
        log.info("ClamAV 病毒扫描已启用: host={} port={}", host, port);
        return new ClamAvScanner(host, port, timeoutMillis, chunkSize);
    }
}
