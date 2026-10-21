package com.wuling.user.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 舌象接口必须进入 MiniAppAuthInterceptor 保护清单，防止已登录用户被误判未登录。
 */
class TongueSecurityConfigTest {

    @Test
    void tongueEndpointsAreProtected() throws Exception {
        Path repoRoot = Paths.get("..").toAbsolutePath().normalize();
        String source = Files.readString(repoRoot.resolve(
                "user-service/src/main/java/com/wuling/user/config/UserSecurityConfig.java"));

        assertTrue(source.contains("\"/api/v1/app/tongue/**\""),
                "UserSecurityConfig 必须保护 /api/v1/app/tongue/**");
    }
}
