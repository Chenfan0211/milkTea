package com.wuling.auth;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RBAC 静态守卫（CI 检查）。
 *
 * <p><b>为什么需要它</b>：2026-09-25 的授权中心重构把「账号 - 角色 - 菜单」
 * 做成可配置后，出现了三类<b>只在运行时才暴露、且后果严重</b>的失败模式：
 * <ol>
 *   <li>超管唯一性只在应用层校验 → 有人手写 SQL 多绑一个超管，界面看不出来；</li>
 *   <li>菜单种子数据与前端路由表脱节 → 动态菜单模式下菜单凭空消失；</li>
 *   <li>授权中心接口忘记加超管校验 → 非超管账号可直连接口改角色。</li>
 * </ol>
 * 本测试把这三类问题变成<b>构建期可发现</b>。
 */
class AdminRbacGuardTest {

    /** 仓库根目录（测试工作目录是 server 模块） */
    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("src/views"))) {
                return p;
            }
        }
        return cwd;
    }

    private static final String MIGRATION = "server/src/main/resources/db/migration/V39__admin_rbac_menu.sql";

    /** 1) 迁移必须存在，且建了 sys_role_menu、给 sys_role/sys_user 补了标记列 */
    @Test
    void rbacMigrationShouldExistAndBeComplete() throws IOException {
        Path file = repoRoot().resolve(MIGRATION);
        assertTrue(Files.exists(file), "RBAC 迁移文件缺失：" + MIGRATION);

        String sql = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS sys_role_menu"),
                "缺少 sys_role_menu 建表 —— 没有它角色无法分配菜单");
        assertTrue(sql.contains("is_builtin"),
                "缺少 sys_role.is_builtin —— 无法表达「内置角色不可改删」");
        assertTrue(sql.contains("is_super"),
                "缺少 sys_user.is_super —— 无法表达「超管账号」");
        assertTrue(sql.contains("R_SUPER"),
                "迁移中未出现 R_SUPER —— 超管唯一性回填逻辑可能丢失");
    }

    /** 2) 前端路由表里的每个菜单 code，迁移种子数据里都要有（防止动态菜单模式下菜单凭空消失） */
    @Test
    void migrationSeedShouldCoverEveryFrontendMenu() throws IOException {
        Path root = repoRoot();
        Path routes = root.resolve("src/router/elegant/routes.ts");
        Path migration = root.resolve(MIGRATION);
        if (!Files.exists(routes) || !Files.exists(migration)) {
            return;
        }

        String routeSrc = Files.readString(routes, StandardCharsets.UTF_8);
        String seed = Files.readString(migration, StandardCharsets.UTF_8);

        // 逐个路由 name 检查（只查 name: 'xxx' 形式，足够覆盖本项目的路由写法）
        List<String> missing = new java.util.ArrayList<>();
        var m = java.util.regex.Pattern.compile("name: '([A-Za-z0-9_-]+)'").matcher(routeSrc);
        int checked = 0;
        while (m.find()) {
            String name = m.group(1);
            // 跳过 constant 路由（登录/403/404/iframe 不在菜单表里）
            if (List.of("login", "403", "404", "500", "iframe-page", "root", "not-found").contains(name)) {
                continue;
            }
            checked++;
            if (!seed.contains("'" + name + "'")) {
                missing.add(name);
            }
        }

        assertTrue(checked >= 40,
                "只解析到 " + checked + " 个路由 name，明显偏少 —— 解析逻辑失效，本测试将失去意义");
        assertTrue(missing.isEmpty(),
                "以下前端路由未在 " + MIGRATION + " 的菜单种子数据中登记，"
                        + "动态菜单模式下会导致菜单缺失（页面存在但侧边栏看不到）：\n  "
                        + String.join("\n  ", missing));
    }

    /** 3) 授权中心的写接口必须带超管校验，且超管角色不可被普通 CRUD 改删 */
    @Test
    void authorizationCenterMustBeProtectedBySuperCheck() throws IOException {
        Path root = repoRoot();

        // 3.1 账号管理控制器：每个写方法都应有 assertSuperOperator
        String account = readIfExists(root.resolve(
                "server/src/main/java/com/wuling/auth/controller/AdminAccountController.java"));
        assertTrue(account.contains("assertSuperOperator"),
                "AdminAccountController 未做超管校验 —— 非超管账号可直连接口增删改账号");
        assertTrue(account.contains("assertAccountMutable"),
                "AdminAccountController 未校验「超管账号不可修改」");
        assertTrue(account.contains("assertSuperRoleAssignable"),
                "AdminAccountController 未校验「超管只能绑定一个账号」");

        // 3.2 角色菜单控制器：写接口必须有超管校验
        String roleMenu = readIfExists(root.resolve(
                "server/src/main/java/com/wuling/auth/controller/AdminRoleMenuController.java"));
        assertTrue(roleMenu.contains("assertSuperOperator"),
                "AdminRoleMenuController 未做超管校验 —— 非超管账号可直连接口改角色菜单");

        // 3.3 通用 CRUD：roles/grants 资源必须被拦（否则可绕过专用接口改角色）
        String crud = readIfExists(root.resolve(
                "server/src/main/java/com/wuling/system/crud/CrudService.java"));
        assertTrue(crud.contains("SUPER_ONLY_RESOURCES"),
                "CrudService 未登记 SUPER_ONLY_RESOURCES —— 非超管可走通用 CRUD 改角色/授权");
        assertTrue(crud.contains("guardSuperOnly") && crud.contains("guardBuiltinRoleWrite"),
                "CrudService 未调用 guardSuperOnly / guardBuiltinRoleWrite");

        // 3.4 后台授权查询：授权中心相关端点必须带超管校验
        String query = readIfExists(root.resolve(
                "server/src/main/java/com/wuling/auth/controller/AdminAuthQueryController.java"));
        assertTrue(query.contains("assertSuperOperator"),
                "AdminAuthQueryController 未做超管校验");
    }

    /**
     * 3.5) 菜单父子关联必须是「两步插入」，不能一步 JOIN。
     *
     * <p><b>为什么单独立这条</b>：2026-09-25 实测踩到 —— 迁移里写成
     * <pre>INSERT INTO sys_menu (...) SELECT ... FROM seed s
     *   LEFT JOIN sys_menu p ON p.code = s.parent_code</pre>
     * 时，JOIN 看到的是 INSERT <b>之前</b>的 sys_menu 快照，
     * 同批插入的父节点对它不可见，于是 53 条菜单全部 parent_id=0，
     * 整棵树被拍平（授权中心子菜单泄漏成顶级菜单）。
     *
     * <p>这个 bug 用「语句能执行成功」是发现不了的 —— 它不报错，
     * 只是静静地给出错误的数据。因此必须在构建期用静态断言锁住写法。
     */
    @Test
    void menuParentLinkMustUseTwoPhaseInsert() throws IOException {
        String sql = Files.readString(repoRoot().resolve(MIGRATION), StandardCharsets.UTF_8);

        assertTrue(sql.contains("WHERE s.parent_code IS NULL"),
                "菜单种子未按「先插父节点」的方式拆分（缺少 WHERE s.parent_code IS NULL）。\n"
                        + "一步 JOIN 插入会让所有菜单的 parent_id 落到 0，整棵菜单树被拍平。");
        assertTrue(sql.contains("WHERE s.parent_code IS NOT NULL"),
                "菜单种子未按「再插子节点」的方式拆分（缺少 WHERE s.parent_code IS NOT NULL）。");

        // 反向断言：不允许出现「同一批插入里 JOIN 自己」的写法
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "INSERT INTO sys_menu[^;]*LEFT JOIN sys_menu", java.util.regex.Pattern.DOTALL
                        | java.util.regex.Pattern.CASE_INSENSITIVE).matcher(sql);
        assertTrue(!m.find(),
                "检测到 `INSERT INTO sys_menu ... LEFT JOIN sys_menu` 的写法 —— "
                        + "JOIN 看不到本批新插入的父节点，parent_id 会全部为 0。"
                        + "请改为「先插父、再插子」两步插入。");

        // 授权中心的三个子菜单必须被登记（本任务的核心菜单）
        for (String code : new String[]{"'auth_role'", "'auth_account'", "'auth_grant'"}) {
            assertTrue(sql.contains(code), "菜单种子缺少 " + code);
        }
    }

    /** 4) 动态菜单链路必须接通：RouteController 不能再是空壳 */
    @Test
    void dynamicMenuEndpointMustNotBeStub() throws IOException {
        String route = readIfExists(repoRoot().resolve(
                "server/src/main/java/com/wuling/auth/controller/RouteController.java"));
        assertTrue(route.contains("AdminMenuService"),
                "RouteController 未接入 AdminMenuService —— /route/getUserRoutes 仍是空壳，"
                        + "动态菜单模式下前端拿不到任何菜单");
        assertTrue(route.contains("menuCodesOf"),
                "/route/isRouteExist 未按账号菜单判定 —— 恒返回 true 会绕过 403 逻辑");
    }

    private String readIfExists(Path path) throws IOException {
        assertTrue(Files.exists(path), "文件不存在：" + path);
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}


