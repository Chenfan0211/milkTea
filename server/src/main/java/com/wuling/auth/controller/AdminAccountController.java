package com.wuling.auth.controller;

import com.wuling.auth.service.AdminMenuService;
import com.wuling.auth.service.AdminRbacGuard;
import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.common.sql.SqlGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台账号管理（运营后台「授权中心 / 账号管理」）。
 *
 * <p><b>本接口解决的核心诉求</b>：角色要绑定的是「可登录运营后台的账号」，
 * 而不是小程序的 {@code app_user}。账号由此处手动新建（用户名 + 初始密码），
 * 创建后即可用该凭据登录，并自动获得所绑角色的菜单权限。
 *
 * <p><b>三条硬规则（均在服务端强制，前端隐藏按钮不算）</b>：
 * <ol>
 *   <li>超管账号信息不可修改，仅允许「重置密码」；</li>
 *   <li>超管角色只能绑一个账号（{@link AdminRbacGuard#assertSuperRoleAssignable}）；</li>
 *   <li>授权中心相关接口仅超管可调（{@link AdminRbacGuard#assertSuperOperator}）。</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/admin/auth/accounts")
public class AdminAccountController {

    /** 用户名规则：字母开头，字母/数字/下划线，3-32 位（避免与登录页正则冲突） */
    private static final java.util.regex.Pattern USERNAME_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z][A-Za-z0-9_]{2,31}$");

    /** 初始密码最小长度。与前端表单校验保持一致。 */
    private static final int MIN_PASSWORD_LENGTH = 6;

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final AdminRbacGuard rbacGuard;
    private final AdminMenuService menuService;

    public AdminAccountController(JdbcTemplate jdbcTemplate,
                                  PasswordEncoder passwordEncoder,
                                  AdminRbacGuard rbacGuard,
                                  AdminMenuService menuService) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.rbacGuard = rbacGuard;
        this.menuService = menuService;
    }

    /**
     * 账号分页列表。
     *
     * <p>返回体里带 {@code roleCodes} / {@code roleNames} / {@code isSuper}，
     * 前端列表可直接展示「角色」列与超管标识，无需二次请求。
     */
    @GetMapping
    public Result<PageResult<Map<String, Object>>> page(@RequestParam(defaultValue = "1") long current,
                                                        @RequestParam(defaultValue = "10") long size,
                                                        @RequestParam(required = false) String username,
                                                        @RequestParam(required = false) String nickName) {
        rbacGuard.assertSuperOperator("查看后台账号列表");

        StringBuilder where = new StringBuilder(" where u.deleted = 0");
        List<Object> args = new ArrayList<>();
        if (username != null && !username.isBlank()) {
            where.append(" and u.username like ?");
            args.add("%" + username.trim() + "%");
        }
        if (nickName != null && !nickName.isBlank()) {
            where.append(" and u.nick_name like ?");
            args.add("%" + nickName.trim() + "%");
        }

        Long total = jdbcTemplate.queryForObject(
                "select count(*) from sys_user u" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(Math.max(0, (current - 1) * size));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select u.id, u.username, u.nick_name, u.status, u.is_super, u.create_time "
                        + "from sys_user u" + where + " order by u.id asc limit ? offset ?",
                pageArgs.toArray());

        List<Map<String, Object>> records = rows.stream().map(this::decorate).toList();
        return Result.ok(PageResult.of(records, current, size, total == null ? 0L : total));
    }

    /** 可绑定的角色列表（供账号表单的角色下拉） */
    @GetMapping("/role-options")
    public Result<List<Map<String, Object>>> roleOptions() {
        rbacGuard.assertSuperOperator("查看角色选项");
        return Result.ok(jdbcTemplate.queryForList(
                "select id, code, name, is_builtin from sys_role "
                        + "where deleted = 0 and status = 1 order by id asc")
                .stream().map(r -> {
                    Map<String, Object> mapped = new LinkedHashMap<>(r);
                    mapped.put("isBuiltin", toBoolean(r.get("is_builtin")));
                    mapped.remove("is_builtin");
                    return mapped;
                }).toList());
    }

    /**
     * 新建账号。
     *
     * <p>密码在这里就被 BCrypt 加密后入库，明文不会落库也不会回显。
     * 若勾选了 {@code R_SUPER}，走超管唯一性校验。
     */
    @PostMapping
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> payload) {
        rbacGuard.assertSuperOperator("新建后台账号");

        String username = asText(payload.get("username"));
        String password = asText(payload.get("password"));
        String nickName = asText(payload.get("nickName"));
        List<String> roleCodes = asList(payload.get("roleCodes"));

        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "用户名需以字母开头，由字母/数字/下划线组成，长度 3-32 位");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "初始密码长度不能少于 " + MIN_PASSWORD_LENGTH + " 位");
        }
        Integer dup = jdbcTemplate.queryForObject(
                "select count(*) from sys_user where username = ? and deleted = 0", Integer.class, username);
        if (dup != null && dup > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户名已存在");
        }
        validateRoleCodes(roleCodes);

        if (roleCodes.contains(AdminRbacGuard.SUPER_ROLE_CODE)) {
            rbacGuard.assertSuperRoleAssignable(-1);
        }

        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    "insert into sys_user (username, password, nick_name, status) values (?, ?, ?, 1)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, username);
            ps.setString(2, passwordEncoder.encode(password));
            ps.setString(3, nickName == null || nickName.isBlank() ? username : nickName);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        long newId = key == null ? 0L : key.longValue();

        replaceRoles(newId, roleCodes);

        return Result.ok(decorate(jdbcTemplate.queryForMap(
                "select id, username, nick_name, status, is_super, create_time from sys_user where id = ?", newId)));
    }

    /**
     * 编辑账号（昵称 / 状态）。
     *
     * <p><b>用户名不可改</b>：它是登录凭据，改名会让「这到底是不是同一个账号」
     * 在审计日志里产生歧义；需求确认按「用户名不可改、昵称可改、密码可改」设计。
     */
    @PutMapping("/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> update(@PathVariable long id, @RequestBody Map<String, Object> payload) {
        rbacGuard.assertSuperOperator("编辑后台账号");
        rbacGuard.assertAccountMutable(id, false);
        rbacGuard.requireAccount(id);

        String nickName = asText(payload.get("nickName"));
        Integer status = asInt(payload.get("status"));

        if (nickName != null) {
            jdbcTemplate.update("update sys_user set nick_name = ? where id = ? and deleted = 0", nickName, id);
        }
        if (status != null) {
            jdbcTemplate.update("update sys_user set status = ? where id = ? and deleted = 0",
                    status == 0 ? 0 : 1, id);
        }
        return Result.ok(decorate(jdbcTemplate.queryForMap(
                "select id, username, nick_name, status, is_super, create_time from sys_user where id = ?", id)));
    }

    /**
     * 重置密码。
     *
     * <p><b>唯一允许作用于超管账号的写操作</b>（需求：「这个账号只能修改密码，
     * 也只能有超级管理员去修改」）。不收旧密码 —— 语义就是超管代改。
     */
    @PutMapping("/{id}/password")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> resetPassword(@PathVariable long id, @RequestBody Map<String, Object> payload) {
        rbacGuard.assertSuperOperator("重置后台账号密码");
        rbacGuard.requireAccount(id);

        String password = asText(payload.get("password"));
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "密码长度不能少于 " + MIN_PASSWORD_LENGTH + " 位");
        }
        jdbcTemplate.update("update sys_user set password = ? where id = ? and deleted = 0",
                passwordEncoder.encode(password), id);
        // 旧 token 在有效期内仍可用（JWT 无状态），但下次登录必须用新密码。
        // 如需「改密即踢下线」，需引入 token 版本号或黑名单，本批不做（见方案风险表）。
        writeAudit("重置密码", id);
        return Result.ok();
    }

    /** 给账号分配角色（覆盖式：传什么就是什么，含超管唯一性校验） */
    @PutMapping("/{id}/roles")
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> assignRoles(@PathVariable long id, @RequestBody Map<String, Object> payload) {
        rbacGuard.assertSuperOperator("分配账号角色");
        Map<String, Object> account = rbacGuard.requireAccount(id);
        rbacGuard.assertAccountMutable(id, false);

        List<String> roleCodes = asList(payload.get("roleCodes"));
        validateRoleCodes(roleCodes);

        if (roleCodes.contains(AdminRbacGuard.SUPER_ROLE_CODE)) {
            rbacGuard.assertSuperRoleAssignable(id);
        }

        // 不允许把「最后一个超管账号」的超管角色摘掉，否则会锁死授权中心
        boolean wasSuper = toBoolean(account.get("is_super"));
        if (wasSuper && !roleCodes.contains(AdminRbacGuard.SUPER_ROLE_CODE)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "不可移除超级管理员的超管角色");
        }

        replaceRoles(id, roleCodes);
        syncSuperFlag(id);
        writeAudit("分配角色", id);

        return Result.ok(decorate(jdbcTemplate.queryForMap(
                "select id, username, nick_name, status, is_super, create_time from sys_user where id = ?", id)));
    }

    /** 逻辑删除账号（超管不可删） */
    @DeleteMapping("/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> delete(@PathVariable long id) {
        rbacGuard.assertSuperOperator("删除后台账号");
        rbacGuard.assertAccountMutable(id, false);

        Long self = com.wuling.security.AdminUser.getUserId();
        if (self != null && self == id) {
            throw new BusinessException(ResultCode.FORBIDDEN, "不能删除当前登录的账号");
        }
        jdbcTemplate.update("update sys_user set deleted = 1, status = 0 where id = ?", id);
        jdbcTemplate.update("update sys_user_role set deleted = 1 where user_id = ?", id);
        writeAudit("删除账号", id);
        return Result.ok();
    }

    // ---------- 内部工具 ----------

    /** 覆盖式写角色绑定（先逻辑删旧，再插新），并同步 is_super */
    private void replaceRoles(long userId, List<String> roleCodes) {
        jdbcTemplate.update("update sys_user_role set deleted = 1 where user_id = ?", userId);
        for (String code : roleCodes) {
            jdbcTemplate.update(
                    "insert into sys_user_role (user_id, role_id) "
                            + "select ?, id from sys_role where code = ? and deleted = 0",
                    userId, code);
        }
        syncSuperFlag(userId);
    }

    /** 按是否绑定 R_SUPER 同步 sys_user.is_super */
    private void syncSuperFlag(long userId) {
        Integer n = jdbcTemplate.queryForObject(
                "select count(*) from sys_user_role ur join sys_role r on r.id = ur.role_id "
                        + "where ur.user_id = ? and ur.deleted = 0 and r.deleted = 0 and r.code = ?",
                Integer.class, userId, AdminRbacGuard.SUPER_ROLE_CODE);
        boolean isSuper = n != null && n > 0;
        jdbcTemplate.update("update sys_user set is_super = ? where id = ?", isSuper ? 1 : 0, userId);
    }

    private void validateRoleCodes(List<String> roleCodes) {
        for (String code : roleCodes) {
            if (!rbacGuard.roleCodeExists(code)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "角色不存在：" + code);
            }
        }
    }

    /** 补角色码/角色名/是否超管，供列表与表单回显 */
    private Map<String, Object> decorate(Map<String, Object> row) {
        Map<String, Object> result = camelize(new LinkedHashMap<>(row));
        long id = Long.parseLong(String.valueOf(result.get("id")));
        List<Map<String, Object>> roles = jdbcTemplate.queryForList(
                "select r.code, r.name from sys_role r join sys_user_role ur on ur.role_id = r.id "
                        + "where ur.user_id = ? and ur.deleted = 0 and r.deleted = 0 order by r.id asc", id);
        result.put("roleCodes", roles.stream().map(r -> String.valueOf(r.get("code"))).toList());
        result.put("roleNames", roles.stream().map(r -> String.valueOf(r.get("name"))).toList());
        result.put("isSuper", toBoolean(result.get("isSuper")));
        result.put("status", toBoolean(row.get("status")) ? 1 : 0);
        // 超管账号：前端据此隐藏 编辑/停用/删除/改角色 按钮（真正的拦截在后端）
        result.put("mutable", !Boolean.TRUE.equals(result.get("isSuper")));
        return result;
    }

    private void writeAudit(String action, long targetId) {
        try {
            jdbcTemplate.update(
                    "insert into audit_log (operator, module, action, target) values (?, ?, ?, ?)",
                    com.wuling.security.AdminUser.getUsername(), "账号管理", action, "账号#" + targetId);
        } catch (Exception ignored) {
            // 审计失败不应阻断业务（与 CrudService 的策略一致）
        }
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

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private Integer asInt(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** tinyint -> boolean（MySQL 驱动可能返回 Integer 或 Boolean） */
    private boolean toBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return !"0".equals(String.valueOf(value)) && !"false".equalsIgnoreCase(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private List<String> asList(Object value) {
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item).trim());
                }
            }
            return out;
        }
        return new ArrayList<>();
    }
}
