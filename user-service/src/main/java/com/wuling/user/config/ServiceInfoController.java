package com.wuling.user.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 服务自述端点（不暴露敏感信息） */
@RestController
public class ServiceInfoController {

    @GetMapping("/internal/service-info")
    public Map<String, Object> info() {
        return Map.of(
                "service", "user-service",
                "phase", "phase-11",
                "status", "up"
        );
    }
}
