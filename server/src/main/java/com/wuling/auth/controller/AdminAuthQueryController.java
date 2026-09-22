package com.wuling.auth.controller;

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
 * 后台授权查询（只读）。
 * 覆盖：后台角色、用户-角色授权、微信绑定、审计日志。
 */
@RestController
@RequestMapping("/api/v1/admin/auth")
public class AdminAuthQueryController {

    private final JdbcTemplate jdbcTemplate;

    public AdminAuthQueryController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/roles")
    public Result<PageResult<Map<String, Object>>> roles(@RequestParam(defaultValue = "1") long current,
                                                         @RequestParam(defaultValue = "10") long size,
                                                         @RequestParam(required = false) String name) {
        String where = " where deleted = 0";
        List<Object> args = new ArrayList<>();
        if (name != null && !name.isBlank()) {
            where += " and (code like ? or name like ?)";
            args.add("%" + name + "%");
            args.add("%" + name + "%");
        }
        return Result.ok(pageOf("sys_role", where, args, "id asc", current, size));
    }

    /** 用户-角色授权列表 */
    @GetMapping("/grants")
    public Result<PageResult<Map<String, Object>>> grants(@RequestParam(defaultValue = "1") long current,
                                                          @RequestParam(defaultValue = "10") long size,
                                                          @RequestParam(required = false) String roleCode) {
        StringBuilder where = new StringBuilder(" where g.deleted = 0");
        List<Object> args = new ArrayList<>();
        if (roleCode != null && !roleCode.isBlank()) {
            where.append(" and g.role_code like ?");
            args.add("%" + roleCode + "%");
        }
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from user_role_grant g" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(Math.max(0, (current - 1) * size));
        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                "select g.id, g.user_id, g.role_code, g.subject_id, g.data_scope, g.grant_by, g.grant_time, g.status,"
                        + " b.name as subject_name, u.nick_name as user_name"
                        + " from user_role_grant g"
                        + " left join biz_subject b on b.id = g.subject_id"
                        + " left join app_user u on u.id = g.user_id"
                        + where + " order by g.id desc limit ? offset ?", pageArgs.toArray());
        return Result.ok(PageResult.of(records.stream().map(this::camelize).toList(), current, size,
                total == null ? 0L : total));
    }

    /** 微信绑定（小程序用户） */
    @GetMapping("/wechat")
    public Result<PageResult<Map<String, Object>>> wechat(@RequestParam(defaultValue = "1") long current,
                                                          @RequestParam(defaultValue = "10") long size,
                                                          @RequestParam(required = false) String nickName) {
        String where = " where deleted = 0";
        List<Object> args = new ArrayList<>();
        if (nickName != null && !nickName.isBlank()) {
            where += " and (nick_name like ? or open_id like ?)";
            args.add("%" + nickName + "%");
            args.add("%" + nickName + "%");
        }
        return Result.ok(pageOf("app_user", where, args, "id asc", current, size));
    }

    /** 审计日志 */
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
