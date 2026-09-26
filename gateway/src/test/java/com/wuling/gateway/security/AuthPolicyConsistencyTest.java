package com.wuling.gateway.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 鉴权清单一致性测试（防止多处清单不同步造成鉴权空档）。
 *
 * <p>背景：受保护路径清单目前维护在四处：
 * <ol>
 *   <li>gateway 的 {@link GatewayAuthPolicy}（网关层前置校验）</li>
 *   <li>server 的 {@code WebConfig}（单体拦截器）</li>
 *   <li>marketing-service 的 {@code MarketingSecurityConfig}（营销拦截器）</li>
 *   <li>product-service 的 {@code ProductSecurityConfig}（第 13 期商品拦截器）</li>
 * </ol>
 *
 * <p>三者不同步会导致「某服务认为要登录、网关却放行」的空档。
 * 拆分期这是真实风险 —— 本次实现中就发现
 * {@code /api/v1/app/gift-cards} 在网关清单中缺失
 * （靠 fail-safe 兜底才没出事）。
 *
 * <p>本测试直接读取源码文本做比对，属「架构约束测试」。
 * 若它失败，说明有清单未同步；请补齐后再提交。
 */
class AuthPolicyConsistencyTest {

    /** 从源码中提取 String 字面量形式的路径清单 */
    private List<String> extractPaths(Path file, String blockMarker) throws IOException {
        if (!Files.exists(file)) {
            fail("清单文件不存在: " + file);
        }
        String src = Files.readString(file);
        // 取 blockMarker 之后到下一个 ");" 之间的内容
        int start = src.indexOf(blockMarker);
        if (start < 0) {
            fail("未找到标记: " + blockMarker + " @ " + file);
        }
        int end = src.indexOf(");", start);
        String block = src.substring(start, end);
        Matcher m = Pattern.compile("\"(/[^\"]+)\"").matcher(block);
        List<String> paths = new java.util.ArrayList<>();
        while (m.find()) {
            paths.add(m.group(1));
        }
        return paths;
    }

    @Test
    void gatewayProtectedShouldCoverServerProtected() throws IOException {
        // 从测试运行目录回溯到仓库根（gateway 模块的父目录）
        Path repoRoot = Paths.get("..").toAbsolutePath().normalize();

        List<String> gatewayProtected = new java.util.ArrayList<>(extractPaths(
                repoRoot.resolve("gateway/src/main/java/com/wuling/gateway/security/GatewayAuthPolicy.java"),
                "PROTECTED_EXACT_PATHS"));
        gatewayProtected.addAll(extractPaths(
                repoRoot.resolve("gateway/src/main/java/com/wuling/gateway/security/GatewayAuthPolicy.java"),
                "PROTECTED_PREFIXES"));

        List<String> serverProtected = extractPaths(
                repoRoot.resolve("server/src/main/java/com/wuling/common/config/WebConfig.java"),
                "addPathPatterns");

        // 忽略带通配符的条目（网关策略用 startsWith，不需要逐条列出 /**）
        List<String> missing = serverProtected.stream()
                .filter(p -> !p.endsWith("/**"))
                .filter(p -> !gatewayProtected.contains(p))
                .toList();

        assertTrue(missing.isEmpty(),
                "以下受保护路径未在网关清单中声明，存在鉴权空档风险: " + missing
                        + "\n请同步 gateway 的 GatewayAuthPolicy.PROTECTED_PREFIXES");
    }

    /**
     * 第 13 期新增：product-service 的管理端拦截清单也必须被网关覆盖。
     *
     * <p>product 拆出后，「管理端商品维护需登录」这一约束同时存在于：
     * <ul>
     *   <li>网关 {@code GatewayAuthPolicy}（{@code /api/v1/admin/**} 兜底需登录）</li>
     *   <li>product-service 的 {@code ProductSecurityConfig}（本服务拦截器）</li>
     * </ul>
     * 前者是兜底规则（前缀匹配），后者是显式清单。本测试确保
     * product-service 声明的每条管理端路径都能被网关的鉴权规则拦住，
     * 避免「服务自己认为要登录、网关却放行」的空档。
     */
    @Test
    void gatewayShouldProtectProductServiceAdminPaths() throws IOException {
        Path repoRoot = Paths.get("..").toAbsolutePath().normalize();

        List<String> productProtected = extractPaths(
                repoRoot.resolve("product-service/src/main/java/com/wuling/product/config/ProductSecurityConfig.java"),
                "addPathPatterns");

        GatewayAuthPolicy policy = new GatewayAuthPolicy();
        List<String> notProtected = productProtected.stream()
                .map(p -> p.replace("/**", "/list"))
                .filter(p -> !policy.requiresAuth(p))
                .toList();

        assertTrue(notProtected.isEmpty(),
                "以下 product-service 受保护路径未被网关鉴权覆盖，存在鉴权空档: " + notProtected);
    }
}
