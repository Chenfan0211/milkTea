package com.wuling.user.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 地址 App 接口必须鉴权；内部快照接口不经小程序拦截器。
 */
class AddressSecurityConfigTest {

    @Test
    void appAddressEndpointsAreProtectedAndInternalEndpointIsNot() throws Exception {
        Path repoRoot = Paths.get("..").toAbsolutePath().normalize();
        String source = Files.readString(repoRoot.resolve(
                "user-service/src/main/java/com/wuling/user/config/UserSecurityConfig.java"));

        assertTrue(source.contains("\"/api/v1/app/addresses/**\""),
                "UserSecurityConfig 必须保护 /api/v1/app/addresses/**");
        assertFalse(source.contains("\"/internal/addresses"),
                "/internal/addresses/** 仅供内部调用，不得经过 MiniAppAuthInterceptor");
    }
}