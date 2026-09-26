package com.wuling.auth.controller;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快捷登录白名单与「口令不进前端产物」的 CI 守卫。
 *
 * <p><b>为什么需要它</b>：2026-09-25 的登录页曾经把 4 个后台账号的弱口令
 * （{@code 123456}）硬编码在 {@code pwd-login.vue} 里，口令随构建产物一起发布，
 * 任何人打开 dist 就能拿到。后来改成服务端免密签发，但这个改动有两条
 * <b>很容易被无意回退</b>的约束：
 * <ol>
 *   <li>白名单只覆盖 4 个演示账号 —— 手滑加一个 {@code admin} 就等于开后门；</li>
 *   <li>前端不得再出现明文口令 —— 下次有人图省事复制粘贴旧代码就破防了。</li>
 * </ol>
 * 本测试把这两条变成构建期可发现。
 */
class QuickLoginWhitelistTest {

    /** 与 V21 种子账号一致的 4 个快捷角色视图 */
    private static final Map<String, String> EXPECTED = Map.of(
            "super", "super",
            "operation", "operator",
            "finance", "Finance",
            "audit", "Audit"
    );

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("src/views"))) {
                return p;
            }
        }
        return cwd;
    }

    /** 1) 白名单必须恰好是这 4 个账号，多一个都不行（防后门扩面） */
    @Test
    void whitelistShouldContainExactlyTheFourDemoAccounts() throws Exception {
        Field field = QuickLoginController.class.getDeclaredField("QUICK_LOGIN_ACCOUNTS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> actual = (Map<String, String>) field.get(null);

        assertEquals(EXPECTED, actual,
                "快捷登录白名单被改动。新增账号前请先确认这是有意的安全决策"
                        + "（该入口免密，等于给账号开后门）。");
    }

    /** 2) 白名单 key 即对外契约，前端按钮与它一一对应，改名会让按钮点不通 */
    @Test
    void whitelistKeysShouldMatchFrontendButtons() throws IOException {
        Path file = repoRoot().resolve("src/views/_builtin/login/modules/pwd-login.vue");
        assertTrue(Files.exists(file), "登录页组件缺失：" + file);
        String vue = Files.readString(file, StandardCharsets.UTF_8);

        for (String key : EXPECTED.keySet()) {
            assertTrue(vue.contains("key: '" + key + "'"),
                    "登录页快捷按钮缺少 key='" + key + "'，该入口将无法点击");
        }
        // 反向：前端不应出现白名单之外的快捷 key，避免按钮存在但服务端拒绝
        assertFalse(vue.contains("key: 'admin'"),
                "登录页出现了白名单外的快捷 key 'admin'");
    }

    /**
     * 3) 前端产物不得含明文口令（本次修复的核心诉求）。
     *
     * <p>只检查<b>去掉注释后的代码</b>：注释里的「旧版口令是 123456」这类说明
     * 是有价值的改动背景（解释了为什么要改成免密），且不会随构建产物执行；
     * 把它们一起拉黑会逼着后人删掉说明，反而更容易被改回去。
     */
    @Test
    void frontendLoginPageMustNotHardcodePassword() throws IOException {
        Path file = repoRoot().resolve("src/views/_builtin/login/modules/pwd-login.vue");
        String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));

        assertFalse(code.contains("123456"),
                "登录页脚本/模板中又出现了明文口令 123456 —— 它会随构建产物发布出去");
        assertFalse(code.matches("(?s).*password\\s*:\\s*'[^']+'.*"),
                "登录页出现了硬编码的 password 字段；免密登录请走 /auth/quick-login/{key}");

        // 反向确认：快捷入口必须真的走服务端免密接口，而不是换个地方存口令
        assertTrue(code.contains("quickLogin"),
                "登录页未调用 authStore.quickLogin，快捷入口可能回退成了本地校验口令");
    }

    /**
     * 去掉行注释（//）与块注释（/* *\/）。
     *
     * <p>够用即可：本文件的模板里没有含 // 的 URL 字面量，无需引入完整解析器。
     */
    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    /** 4) 快捷登录必须复用登录限流，否则它是一个无限次尝试的免锁入口 */
    @Test
    void quickLoginShouldReuseLoginAttemptGuard() throws IOException {
        Path file = repoRoot().resolve(
                "auth-service/src/main/java/com/wuling/auth/controller/QuickLoginController.java");
        String src = Files.readString(file, StandardCharsets.UTF_8);

        assertTrue(src.contains("loginGuard.lockedSeconds"),
                "快捷登录未检查锁定状态，绕过了 /auth/login 的爆破防护");
        assertTrue(src.contains("loginGuard.recordSuccess"),
                "快捷登录成功未清零失败计数，会导致计数残留");
        assertTrue(src.contains("isEnabled()"),
                "快捷登录未校验账号状态，停用账号可借该入口复活");
        assertTrue(src.contains("QUICK_LOGIN"),
                "快捷登录未写审计日志，事后无法追溯谁用免密进了后台");
    }

    /** 5) 开关必须可关闭：正式上线要能一键摘掉整个入口 */
    @Test
    void quickLoginShouldBeDisableableByConfig() throws IOException {
        Path file = repoRoot().resolve(
                "auth-service/src/main/java/com/wuling/auth/controller/QuickLoginController.java");
        String src = Files.readString(file, StandardCharsets.UTF_8);

        assertTrue(src.contains("app.quick-login.enabled"),
                "快捷登录缺少开关配置，上线时只能改代码才能关闭");

        // 安全配置里必须放行这两个接口，否则登录页拿不到开关、按钮点了 401
        Path security = repoRoot().resolve(
                "auth-service/src/main/java/com/wuling/auth/security/SecurityConfig.java");
        String secSrc = Files.readString(security, StandardCharsets.UTF_8);
        List.of("/auth/quick-login/enabled", "/auth/quick-login/*").forEach(path ->
                assertTrue(secSrc.contains(path),
                        "SecurityConfig 未放行 " + path + "，快捷登录会被 401 拦截"));
    }
}
