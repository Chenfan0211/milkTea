package com.wuling.auth.controller;

import com.wuling.auth.service.AdminRbacGuard;
import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import com.wuling.common.sql.SqlGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台授权查询（运营后台「授权中心」）。
 *
 * <p>覆盖：后台角色、后台账号-角色授权记录、审计日志。
 *
 * <p><b>2026-09-25 口径调整</b>：原「角色授权记录」读的是 {@code user_role_grant}
 * ——那是<b>小程序用户</b>的经营角色授权（门店/投资人/资源方），
 * 与「可登录运营后台的账号」是两套体系，混在同名菜单下造成语义错位。
 * 现 {@code /grants} 已切到 {@code sys_user_role}（后台账号 ↔ 后台角色）。
 *
 * <p>{@code /wechat} 保留但不再有后台入口菜单：需求确认「角色不再与小程序用户绑定」，
 * 页面已下线。接口暂留只读能力，避免其他调用方突然 404；后续可择机移除。
 *
 * <p><b>权限</b>：本控制器属于「授权中心」，按需求「只有超级管理员才有该菜单权限」，
 * 故所有端点均校验超管身份 —— 菜单藏起来不够，接口也必须挡。
 */
@RestController
@RequestMapping("/api/v1/admin/auth")
public class AdminAuthQueryController {

    private final JdbcTemplate jdbcTemplate;
    private final AdminRbacGuard rbacGuard;

    public AdminAuthQueryController(JdbcTemplate jdbcTemplate, AdminRbacGuard rbacGuard) {
        this.jdbcTemplate = jdbcTemplate;
        this.rbacGuard = rbacGuard;
    }

    /**
     * 后台角色列表。
     *
     * <p>附带「已绑定账号数 / 已分配菜单数 / 是否内置」：
     * 「角色与权限」页需要一眼看出哪些角色在用、哪些还是空壳，
     * 也用于识别超管行（内置角色）以禁用其编辑/删除按钮。
     */
    @GetMapping("/roles")
    public Result<PageResult<Map<String, Object>>> roles(@RequestParam(defaultValue = "1") long current,
                                                         @RequestParam(defaultValue = "10") long size,
                                                         @RequestParam(required = false) String name) {
        rbacGuard.assertSuperOperator("查看角色列表");

        StringBuilder where = new StringBuilder(" where r.deleted = 0");
        List<Object> args = new ArrayList<>();
        if (name != null && !name.isBlank()) {
            where.append(" and (r.code like ? or r.name like ?)");
            args.add("%" + name + "%");
            args.add("%" + name + "%");
        }
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from sys_role r" + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(Math.max(0, (current - 1) * size));

        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                "select r.id, r.code, r.name, r.data_scope, r.status, r.is_builtin, r.create_time,"
                        + " (select count(*) from sys_user_role ur where ur.role_id = r.id and ur.deleted = 0)"
                        + "   as bound_account_count,"
                        + " (select count(*) from sys_role_menu rm where rm.role_id = r.id and rm.deleted = 0)"
                        + "   as menu_count"
                        + " from sys_role r" + where + " order by r.id asc limit ? offset ?",
                pageArgs.toArray());
        return Result.ok(PageResult.of(records.stream().map(this::camelize).toList(), current, size,
                total == null ? 0L : total));
    }

    /**
     * 角色授权记录（后台账号 ↔ 后台角色）。
     *
     * <p>即「谁被授予了哪个后台角色」，是账号-角色绑定的审计视图。
     */
    @GetMapping("/grants")
    public Result<PageResult<Map<String, Object>>> grants(@RequestParam(defaultValue = "1") long current,
                                                          @RequestParam(defaultValue = "10") long size,
                                                          @RequestParam(required = false) String roleCode,
                                                          @RequestParam(required = false) String username) {
        rbacGuard.assertSuperOperator("查看角色授权记录");

        StringBuilder where = new StringBuilder(" where ur.deleted = 0");
        List<Object> args = new ArrayList<>();
        if (roleCode != null && !roleCode.isBlank()) {
            where.append(" and r.code like ?");
            args.add("%" + roleCode + "%");
        }
        if (username != null && !username.isBlank()) {
            where.append(" and u.username like ?");
            args.add("%" + username + "%");
        }
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from sys_user_role ur"
                        + " join sys_user u on u.id = ur.user_id"
                        + " join sys_role r on r.id = ur.role_id" + where,
                Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(Math.max(0, (current - 1) * size));

        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                "select ur.id, u.username, u.nick_name, r.code as role_code, r.name as role_name,"
                        + "       r.is_builtin, ur.create_time, u.status"
                        + " from sys_user_role ur"
                        + " join sys_user u on u.id = ur.user_id"
                        + " join sys_role r on r.id = ur.role_id"
                        + where + " order by ur.id desc limit ? offset ?", pageArgs.toArray());
        return Result.ok(PageResult.of(records.stream().map(this::camelize).toList(), current, size,
                total == null ? 0L : total));
    }

    /**
     * 小程序用户列表（只读备用）。
     *
     * <p>后台入口菜单「微信账号绑定」已于 2026-09-25 下线，
     * 接口保留以免其他调用方 404；不参与账号-角色绑定。
     */
    @GetMapping("/wechat")
    public Result<PageResult<Map<String, Object>>> wechat(@RequestParam(defaultValue = "1") long current,
                                                          @RequestParam(defaultValue = "10") long size,
                                                          @RequestParam(required = false) String nickName) {
        rbacGuard.assertSuperOperator("查看小程序用户列表");

        String where = " where deleted = 0";
        List<Object> args = new ArrayList<>();
        if (nickName != null && !nickName.isBlank()) {
            where += " and (nick_name like ? or open_id like ?)";
            args.add("%" + nickName + "%");
            args.add("%" + nickName + "%");
        }
        return Result.ok(pageOf("app_user", where, args, "id asc", current, size));
    }

    /** 审计日志（系统审计模块同样使用，故不限超管） */
    @GetMapping("/audit")
    public Result<PageResult<Map<String, Object>>> audit(@RequestParam(defaultValue = "1") long current,
                                                         @RequestParam(defaultValue = "10") long size,
                                                         @RequestParam(required = false) String module,
                                                         @RequestParam(required = false) String action) {
        StringBuilder where = new StringBuilder(" where deleted = 0");
        List<Object> args = new ArrayList<>();
        if (module != null && !module.isBlank()) {
            where.append(" and module like ?");
            args.add("%" + module + "%");
        }
        if (action != null && !action.isBlank()) {
            where.append(" and action like ?");
            args.add("%" + action + "%");
        }
        return Result.ok(pageOf("audit_log", where.toString(), args, "id desc", current, size));
    }

    /** 写入审计日志（供前端关键操作调用） */
    @PostMapping("/audit")
    public Result<Void> writeAudit(@RequestBody Map<String, Object> payload) {
        jdbcTemplate.update("insert into audit_log (operator, module, action, target, before_value, after_value, reason, ip) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                payload.get("operator"), payload.get("module"), payload.get("action"), payload.get("target"),
                payload.get("beforeValue"), payload.get("afterValue"), payload.get("reason"), payload.get("ip"));
        return Result.ok();
    }

    // ---------- 内部工具 ----------

    /**
     * 通用分页查询。
     *
     * SQL 安全：table / orderBy 由调用方传入，虽均为代码常量，
     * 但仍强制经 SqlGuard 校验标识符，避免后续误传入变量导致注入（见改造方案 3.1 S-2）。
     */
    private PageResult<Map<String, Object>> pageOf(String table, String where, List<Object> args,
                                                  String orderBy, long current, long size) {
        String safeTable = SqlGuard.ident(table);
        String safeOrderBy = SqlGuard.orderBy(orderBy);
        Long total = jdbcTemplate.queryForObject("select count(*) from " + safeTable + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(Math.max(0, (current - 1) * size));
        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                "select * from " + safeTable + where + " order by " + safeOrderBy + " limit ? offset ?", pageArgs.toArray());
        return PageResult.of(records.stream().map(this::camelize).toList(), current, size,
                total == null ? 0L : total);
    }

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
