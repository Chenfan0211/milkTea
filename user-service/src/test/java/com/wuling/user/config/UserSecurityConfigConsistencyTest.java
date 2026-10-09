package com.wuling.user.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 用户服务鉴权拦截清单一致性测试（防「已登录却提示未登录」回归）。
 *
 * <p>背景：{@link UserSecurityConfig} 按「精确清单」注册
 * {@code MiniAppAuthInterceptor}，只有登记过的路径才会解析 JWT 并注入
 * {@code CurrentUser}。若 controller 新增了需要登录的接口却忘记登记，
 * 请求会穿透拦截器直达 controller，导致 {@code CurrentUser.require()}
 * 抛「未登录或登录已过期」—— 表现为「已登录用户却提示未登录」。
 *
 * <p>本测试直接读取 {@code MiniAppAuthController} 的源码提取所有 mapping，
 * 剔除公开入口后，校验它们全部出现在拦截清单里。属「架构约束测试」。
 */
class UserSecurityConfigConsistencyTest {

    /** 公开接口（发证 / 注册 / 发短信入口，无需 JWT，与网关 PUBLIC 白名单一致） */
    private static final List<String> PUBLIC_ENDPOINTS = List.of(
            "/api/v1/app/auth/wx-login",
            "/api/v1/app/auth/register-by-phone",
            "/api/v1/app/auth/register-by-sms",
            "/api/v1/app/auth/sms/send"
    );

    /** 提取 controller 里所有方法级 mapping 路径 */
    private List<String> extractControllerMappings(Path file) throws IOException {
        if (!Files.exists(file)) {
            fail("控制器文件不存在: " + file);
        }
        String src = Files.readString(file);
        List<String> mappings = new ArrayList<>();
        Matcher m = Pattern.compile("@(?:Get|Post|Put|Delete|Patch)Mapping\\(\"([^\"]+)\"\\)").matcher(src);
        while (m.find()) {
            mappings.add("/api/v1/app/auth" + m.group(1));
        }
        return mappings;
    }

    /** 提取拦截清单里的路径 */
    private List<String> extractInterceptorPaths(Path file) throws IOException {
        if (!Files.exists(file)) {
            fail("清单文件不存在: " + file);
        }
        String src = Files.readString(file);
        int start = src.indexOf("addPathPatterns");
        if (start < 0) {
            fail("未找到 addPathPatterns: " + file);
        }
        int end = src.indexOf(");", start);
        String block = src.substring(start, end);
        List<String> paths = new ArrayList<>();
        Matcher m = Pattern.compile("\"(/[^\"]+)\"").matcher(block);
        while (m.find()) {
            paths.add(m.group(1));
        }
        return paths;
    }

    @Test
    void protectedControllerMappingsShouldBeRegisteredInInterceptor() throws IOException {
        Path repoRoot = Paths.get("..").toAbsolutePath().normalize();

        List<String> controllerMappings = extractControllerMappings(
                repoRoot.resolve("user-service/src/main/java/com/wuling/user/controller/MiniAppAuthController.java"));
        List<String> interceptorPaths = extractInterceptorPaths(
                repoRoot.resolve("user-service/src/main/java/com/wuling/user/config/UserSecurityConfig.java"));

        List<String> missing = controllerMappings.stream()
                .filter(p -> !PUBLIC_ENDPOINTS.contains(p))
                .filter(p -> !interceptorPaths.contains(p))
                .toList();

        assertTrue(missing.isEmpty(),
                "以下需要登录的接口未登记到 UserSecurityConfig 拦截清单，"
                        + "会导致「已登录用户却提示未登录」: " + missing
                        + "\n请同步 user-service 的 UserSecurityConfig.addPathPatterns");
    }
}