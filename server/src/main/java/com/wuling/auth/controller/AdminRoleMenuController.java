package com.wuling.auth.controller;

import com.wuling.auth.service.AdminMenuService;
import com.wuling.auth.service.AdminRbacGuard;
import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色与权限（运营后台「授权中心 / 角色与权限」）。
 *
 * <p><b>本接口解决的核心诉求</b>：角色要能分配菜单权限，
 * 账号绑定角色后登录即拥有该角色的菜单。
 *
 * <p>写接口仅超管可调；读接口（菜单树）同样仅超管可调，
 * 因为整个「授权中心」按需求只对超管开放。
 */
@RestController
@RequestMapping("/api/v1/admin/auth/roles")
public class AdminRoleMenuController {

    private final AdminMenuService menuService;
    private final AdminRbacGuard rbacGuard;

    public AdminRoleMenuController(AdminMenuService menuService, AdminRbacGuard rbacGuard) {
        this.menuService = menuService;
        this.rbacGuard = rbacGuard;
    }

    /**
     * 全量菜单树（扁平 + 树两种形态一起返回）。
     *
     * <p>为什么同时给扁平与树：前端权限树（NTree）需要树结构；
     * 而「显示已勾选数量」这类统计用扁平列表更省事。
     */
    @GetMapping("/menu-tree")
    public Result<Map<String, Object>> menuTree() {
        rbacGuard.assertSuperOperator("查看菜单树");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tree", menuService.menuTree());
        data.put("flat", menuService.allMenus());
        return Result.ok(data);
    }

    /** 某角色已勾选的菜单 code 列表（权限树回显用） */
    @GetMapping("/{id}/menus")
    public Result<List<String>> roleMenus(@PathVariable long id) {
        rbacGuard.assertSuperOperator("查看角色菜单权限");
        rbacGuard.requireRole(id);
        return Result.ok(menuService.menuCodesOfRole(id));
    }

    /**
     * 保存角色菜单权限（覆盖式）。
     *
     * <p>父节点选择语义由前端保证一致性（勾子节点时自动补父节点），
     * 后端只负责落库。之所以不在后端自动补父：菜单树是前端渲染的，
     * 「半选的父节点」在不同 UI 库下语义不同，交给前端更可控。
     */
    @PutMapping("/{id}/menus")
    public Result<List<String>> saveRoleMenus(@PathVariable long id, @RequestBody Map<String, Object> payload) {
        rbacGuard.assertSuperOperator("分配角色菜单权限");
        if (rbacGuard.isBuiltinRole(id)) {
            // 内置角色（超管）直通全部菜单，不给它写关联表：
            // 否则每新增菜单都要记得补关联，漏一次就把超管锁在门外。
            throw new BusinessException(ResultCode.FORBIDDEN,
                    "内置角色（超级管理员）无需分配菜单权限，默认拥有全部菜单");
        }

        List<String> codes = new ArrayList<>();
        Object raw = payload.get("menuCodes");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    codes.add(String.valueOf(item).trim());
                }
            }
        }
        menuService.saveRoleMenus(id, codes);
        return Result.ok(menuService.menuCodesOfRole(id));
    }
}


