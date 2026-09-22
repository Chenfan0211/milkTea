package com.wuling.auth;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 服务自述端点。
 *
 * 第 2 期用于验证模块可独立启动与访问；
 * 第 3 期接入 Nacos 后，此端点可作为服务健康标识。
 */
@RestController
public class ServiceInfoController {

    /** 服务名与阶段标识（不含任何敏感信息） */
    @GetMapping("/internal/service-info")
    public Map<String, Object> info() {
        return Map.of(
                "service", "auth-service",
                "phase", "phase-2-skeleton",
                "status", "up"
        );
    }
}
