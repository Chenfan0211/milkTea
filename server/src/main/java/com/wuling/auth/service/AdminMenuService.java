package com.wuling.auth.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台菜单权限服务（账号 -> 角色 -> 菜单）。
 *
 * <p>数据来源：{@code sys_user} -> {@code sys_user_role} -> {@code sys_role}
 * -> {@code sys_role_menu} -> {@code sys_menu}。
 *
 * <p><b>超管直通规则</b>：超级管理员不查 {@code sys_role_menu}，直接返回全部菜单。
 * 这样做的原因：若超管也走关联表，则每新增一个菜单都必须记得给 R_SUPER 补关联，
 * 一旦漏掉就会把超管自己锁在门外 —— 这个失败模式代价太高，不值得省这一次查询。
 */
@Service
public class AdminMenuService {

    private final JdbcTemplate jdbcTemplate;
    private final AdminRbacGuard rbacGuard;

    public AdminMenuService(JdbcTemplate jdbcTemplate, AdminRbacGuard rbacGuard) {
        this.jdbcTemplate = jdbcTemplate;
        this.rbacGuard = rbacGuard;
    }

    /**
     * 取某账号可见的菜单列表（扁平，已按 order_num 排序）。
     *
     * @param userId 后台账号 id
     * @return 菜单行，字段名与前端菜单渲染所需一致
     */
    public List<Map<String, Object>> menusOf(long userId) {
        List<Map<String, Object>> rows;
        if (rbacGuard.isSuperAccount(userId)) {
            rows = jdbcTemplate.queryForList(
                    "select id, parent_id, code, name, path, component, icon, order_num, type, feature_flag "
                            + "from sys_menu where deleted = 0 order by order_num asc, id asc");
        } else {
            rows = jdbcTemplate.queryForList(
                    "select distinct m.id, m.parent_id, m.code, m.name, m.path, m.component, m.icon, "
                            + "       m.order_num, m.type, m.feature_flag "
                            + "from sys_menu m "
                            + "join sys_role_menu rm on rm.menu_id = m.id and rm.deleted = 0 "
                            + "join sys_role r on r.id = rm.role_id and r.deleted = 0 and r.status = 1 "
                            + "join sys_user_role ur on ur.role_id = r.id and ur.deleted = 0 "
                            + "where ur.user_id = ? and m.deleted = 0 "
                            + "order by m.order_num asc, m.id asc",
                    userId);
        }
        return rows.stream().map(this::camelize).toList();
    }

    /**
     * 取菜单 id 的扁平集合（用于判断某账号是否有权访问某个路由 code）。
     *
     * <p>用途：{@code /route/isRouteExist} 的权限判定，避免前端直接敲 URL 绕过菜单。
     */
    public List<String> menuCodesOf(long userId) {
        return menusOf(userId).stream()
                .map(r -> String.valueOf(r.get("code")))
                .filter(c -> !"null".equals(c))
                .toList();
    }

    /** 角色已勾选的菜单 code 列表（供「角色与权限」页的权限树回显） */
    public List<String> menuCodesOfRole(long roleId) {
        return jdbcTemplate.queryForList(
                "select m.code from sys_menu m "
                        + "join sys_role_menu rm on rm.menu_id = m.id and rm.deleted = 0 "
                        + "where rm.role_id = ? and m.deleted = 0 and m.code is not null "
                        + "order by m.order_num asc, m.id asc",
                String.class, roleId);
    }

    /** 全量菜单（供权限树渲染，含 parent 关系） */
    public List<Map<String, Object>> allMenus() {
        return jdbcTemplate.queryForList(
                        "select id, parent_id, code, name, path, component, icon, order_num, type, feature_flag "
                                + "from sys_menu where deleted = 0 order by order_num asc, id asc")
                .stream().map(this::camelize).toList();
    }

    /**
     * 组装为前端可用树（parent 指向 code 而非 id）。
     *
     * <p>为什么用 code 作为父子键：前端路由是按 name 索引的，
     * 用 code 可以直接与前端路由表对齐，省掉一层 id->name 映射。
     */
    public List<Map<String, Object>> menuTree() {
        List<Map<String, Object>> flat = allMenus();
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> row : flat) {
            byId.put(String.valueOf(row.get("id")), row);
            row.put("children", new ArrayList<Map<String, Object>>());
        }
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> row : flat) {
            String parentId = String.valueOf(row.get("parentId"));
            Map<String, Object> parent = byId.get(parentId);
            if (parent == null || "0".equals(parentId) || "null".equals(parentId)) {
                roots.add(row);
            } else {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children = (List<Map<String, Object>>) parent.get("children");
                children.add(row);
            }
        }
        return roots;
    }

    /** 覆盖式保存某角色的菜单权限（先清后插，保证勾掉的真的被移除） */
    public void saveRoleMenus(long roleId, List<String> menuCodes) {
        jdbcTemplate.update("delete from sys_role_menu where role_id = ?", roleId);
        if (menuCodes == null || menuCodes.isEmpty()) {
            return;
        }
        for (String code : menuCodes) {
            if (code == null || code.isBlank()) {
                continue;
            }
            jdbcTemplate.update(
                    "insert into sys_role_menu (role_id, menu_id) "
                            + "select ?, id from sys_menu where code = ? and deleted = 0",
                    roleId, code);
        }
    }

    /** 数据库列名（下划线）转驼峰，与项目其它接口返回口径一致 */
    private Map<String, Object> camelize(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((k, v) -> {
            StringBuilder sb = new StringBuilder();
            boolean upper = false;
            for (char c : k.toCharArray()) {
                if (c == '_') {
                    upper = true;
                } else {
                    sb.append(upper ? Character.toUpperCase(c) : c);
                    upper = false;
                }
            }
            result.put(sb.toString(), v);
        });
        return result;
    }
}
