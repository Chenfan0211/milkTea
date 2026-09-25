package com.wuling.auth.controller;

import com.wuling.auth.service.AdminMenuService;
import com.wuling.common.api.Result;
import com.wuling.security.AdminUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台路由（菜单）接口 —— 动态菜单模式的唯一数据源。
 *
 * <p><b>背景</b>：此前 {@code getUserRoutes()} 恒返回空数组，前端只能靠
 * {@code VITE_AUTH_ROUTE_MODE=static} + {@code src/constants/admin.ts} 里
 * 写死的 {@code roles} 数组过滤菜单。后果是「改菜单权限必须改代码重新发版」，
 * 与需求「角色分配菜单 + 账号绑角色登录即有对应菜单」直接冲突。
 *
 * <p>本控制器把菜单来源交给数据库（{@code sys_menu} + {@code sys_role_menu}），
 * 前端切到 {@code dynamic} 模式后即可「后台勾一下、刷新即生效」。
 *
 * <p><b>返回结构</b>对齐 soybean-admin 的 dynamic 模式约定：
 * {@code { routes: ElegantRoute[], home: string }}，
 * 其中 {@code component} 必须是前端 {@code src/router/elegant/imports.ts} 里
 * 已注册的 key（如 {@code layout.base$view.home}），否则前端 transform 会取不到组件。
 */
@RestController
public class RouteController {

    /** 首页路由 key（与 .env 的 VITE_ROUTE_HOME 保持一致） */
    private static final String HOME = "home";

    private final AdminMenuService menuService;

    public RouteController(AdminMenuService menuService) {
        this.menuService = menuService;
    }

    @GetMapping("/route/getConstantRoutes")
    public Result<List<Object>> getConstantRoutes() {
        return Result.ok(List.of());
    }

    /**
     * 当前登录账号可见的路由树。
     *
     * <p>数据链路：登录账号 -> {@code sys_user_role} -> {@code sys_role}
     * -> {@code sys_role_menu} -> {@code sys_menu}。
     * 超管直通全部菜单（见 {@link AdminMenuService#menusOf}）。
     *
     * <p>未登录（{@link AdminUser#getUserId()} 为 null）时返回空路由，
     * 而不是抛异常：前端 {@code initAuthRoute} 里若接口报错会触发
     * {@code authStore.resetStore()} 把用户踢出登录，过于激进；
     * 返回空菜单由前端自行跳 403 更可控。
     */
    @GetMapping("/route/getUserRoutes")
    public Result<Map<String, Object>> getUserRoutes() {
        Long userId = AdminUser.getUserId();
        if (userId == null) {
            return Result.ok(Map.of("routes", List.of(), "home", HOME));
        }

        List<Map<String, Object>> menus = menuService.menusOf(userId);
        List<Map<String, Object>> routes = buildRouteTree(menus);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("routes", routes);
        // 首页兜底：账号若没有 home 菜单权限，仍以 home 作为落点（前端路由本身存在），
        // 避免出现「登录后无处可去」的白屏。
        data.put("home", HOME);
        return Result.ok(data);
    }

    /**
     * 路由名是否存在（供前端 404/403 判定）。
     *
     * <p>此前恒返回 true，等于告诉前端「任何路径都存在」，
     * 会绕过「路径存在但无权限应跳 403」的逻辑。
     * 现按当前账号的菜单 code 判定。
     */
    @GetMapping("/route/isRouteExist")
    public Result<Boolean> isRouteExist(@RequestParam(required = false) String routeName) {
        Long userId = AdminUser.getUserId();
        if (userId == null) {
            return Result.ok(false);
        }
        if (routeName == null || routeName.isBlank()) {
            return Result.ok(false);
        }
        boolean exists = menuService.menuCodesOf(userId).contains(routeName);
        return Result.ok(exists);
    }

    // ---------- 内部工具 ----------

    /**
     * 扁平菜单 -> 前端需要的嵌套路由结构。
     *
     * <p>输出形如：
     * <pre>
     * { name, path, component, meta: { title, icon, order, i18nKey:null, hideInMenu?, keepAlive?, featureFlag? } }
     * </pre>
     *
     * <p>注意 {@code name}/{@code component} 必须与前端 {@code src/router/elegant/imports.ts}
     * 的键完全一致（都取自路由 name），因此两边都从 {@code sys_menu.code} 派生。
     */
    private List<Map<String, Object>> buildRouteTree(List<Map<String, Object>> menus) {
        Map<String, Map<String, Object>> nodeByCode = new LinkedHashMap<>();
        for (Map<String, Object> menu : menus) {
            String code = str(menu.get("code"));
            if (code == null) {
                continue;
            }
            nodeByCode.put(code, toRoute(menu));
        }

        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> menu : menus) {
            String code = str(menu.get("code"));
            if (code == null) {
                continue;
            }
            Map<String, Object> node = nodeByCode.get(code);
            Map<String, Object> parent = findParent(menus, menu, nodeByCode);
            if (parent == null) {
                roots.add(node);
            } else {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children =
                        (List<Map<String, Object>>) parent.computeIfAbsent("children", k -> new ArrayList<>());
                children.add(node);
            }
        }
        return roots;
    }

    /** 按 parentId 反查父节点（父子都以 code 为业务键，id 只用于定位） */
    private Map<String, Object> findParent(List<Map<String, Object>> menus,
                                           Map<String, Object> child,
                                           Map<String, Map<String, Object>> nodeByCode) {
        long parentId = child.get("parentId") == null ? 0L : Long.parseLong(String.valueOf(child.get("parentId")));
        if (parentId == 0L) {
            return null;
        }
        for (Map<String, Object> menu : menus) {
            if (menu.get("id") != null && Long.parseLong(String.valueOf(menu.get("id"))) == parentId) {
                return nodeByCode.get(str(menu.get("code")));
            }
        }
        return null;
    }

    /** 单条菜单 -> 单条路由 */
    private Map<String, Object> toRoute(Map<String, Object> menu) {
        Map<String, Object> route = new LinkedHashMap<>();
        route.put("name", str(menu.get("code")));
        route.put("path", str(menu.get("path")));
        route.put("component", str(menu.get("component")));

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("title", str(menu.get("name")));
        meta.put("icon", menu.get("icon"));
        meta.put("order", menu.get("orderNum"));
        meta.put("i18nKey", null);
        // hideInMenu / hidden 详情页：前端据此不出现在侧边栏，但仍可跳转与鉴权
        if ("hidden".equals(str(menu.get("type")))) {
            meta.put("hideInMenu", true);
        }
        // 功能开关：营销类菜单依赖它，遗漏会导致这些菜单被前端守卫判为「功能未开启」跳 403
        if (menu.get("featureFlag") != null) {
            meta.put("featureFlag", menu.get("featureFlag"));
        }
        route.put("meta", meta);
        return route;
    }

    private String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        return s.isBlank() ? null : s;
    }
}
