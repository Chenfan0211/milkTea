package com.wuling.auth.service;

import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台 RBAC 强约束（服务端权威校验）。
 *
 * <p><b>为什么必须放在服务端</b>：前端的「隐藏按钮」只是体验优化，
 * 任何人拿到 JWT 都能直接调接口。超管相关的三条硬规则
 * （唯一、不可改删、只有超管能进授权中心）必须在服务端拒绝，
 * 否则等于没有约束。
 *
 * <p>三条规则对应需求原文：
 * <ol>
 *   <li>「超级管理员的信息不能修改」→ {@link #assertAccountMutable}；</li>
 *   <li>「只能拥有一个超级管理员，只能绑定一个账号」→ {@link #assertSuperRoleAssignable}；</li>
 *   <li>「只有超级管理员才有这个菜单的权限」→ {@link #assertSuperOperator} +
 *       {@link AdminMenuService} 的菜单过滤。</li>
 * </ol>
 */
@Service
public class AdminRbacGuard {

    /** 超级管理员角色码（与前端 ADMIN_ROLE.SUPER 一致） */
    public static final String SUPER_ROLE_CODE = "R_SUPER";

    private final JdbcTemplate jdbcTemplate;

    public AdminRbacGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 账号是否为超级管理员 */
    public boolean isSuperAccount(long userId) {
        Integer n = jdbcTemplate.queryForObject(
                "select count(*) from sys_user where id = ? and is_super = 1 and deleted = 0",
                Integer.class, userId);
        return n != null && n > 0;
    }

    /** 角色是否为内置角色（内置角色不可改名/改码/删除） */
    public boolean isBuiltinRole(long roleId) {
        Integer n = jdbcTemplate.queryForObject(
                "select count(*) from sys_role where id = ? and is_builtin = 1 and deleted = 0",
                Integer.class, roleId);
        return n != null && n > 0;
    }

    /** 角色是否为超级管理员角色 */
    public boolean isSuperRole(long roleId) {
        Integer n = jdbcTemplate.queryForObject(
                "select count(*) from sys_role where id = ? and code = ? and deleted = 0",
                Integer.class, roleId, SUPER_ROLE_CODE);
        return n != null && n > 0;
    }

    /** 当前登录账号是否为超管（未登录/非管理端上下文时返回 false） */
    public boolean currentIsSuper() {
        Long userId = com.wuling.security.AdminUser.getUserId();
        return userId != null && isSuperAccount(userId);
    }

    /**
     * 当前操作者必须是超级管理员。
     *
     * <p>用于「授权中心」下所有写接口 —— 需求要求该菜单仅超管可见，
     * 那么它的写接口自然也应当只有超管可调。
     */
    public void assertSuperOperator(String action) {
        if (!currentIsSuper()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "仅超级管理员可执行该操作：" + action);
        }
    }

    /**
     * 被操作的账号必须可变。
     *
     * <p>需求：「超级管理员的信息不能修改」「这个账号只能修改密码」。
     * 故超管账号只放行「改密码」这一条路径，其余（编辑昵称/停用/删除/改角色）一律拒绝。
     *
     * @param targetUserId 被操作账号
     * @param allowPasswordOnly true 表示本次操作是改密码（唯一放行项）
     */
    public void assertAccountMutable(long targetUserId, boolean allowPasswordOnly) {
        if (!isSuperAccount(targetUserId)) {
            return;
        }
        if (allowPasswordOnly) {
            return;
        }
        throw new BusinessException(ResultCode.FORBIDDEN,
                "超级管理员账号的信息不可修改（仅允许由超级管理员本人重置密码）");
    }

    /**
     * 校验「超管角色」的绑定是否合法。
     *
     * <p>需求：「只能拥有一个超级管理员」「超级管理员只能绑定一个账号」。
     * 若目标账号已经是超管账号（重复绑定自己），直接放行（幂等）；
     * 否则若已存在任何超管账号，则拒绝。
     */
    public void assertSuperRoleAssignable(long targetUserId) {
        if (isSuperAccount(targetUserId)) {
            return;
        }
        Integer n = jdbcTemplate.queryForObject(
                "select count(*) from sys_user where is_super = 1 and deleted = 0", Integer.class);
        if (n != null && n > 0) {
            throw new BusinessException(ResultCode.FORBIDDEN, "超级管理员只能有一个账号，已存在超级管理员");
        }
    }

    /** 角色被删除前的前置校验：内置角色不可删除 */
    public void assertRoleDeletable(long roleId) {
        if (isBuiltinRole(roleId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "内置角色不可删除");
        }
        Integer n = jdbcTemplate.queryForObject(
                "select count(*) from sys_user_role where role_id = ? and deleted = 0", Integer.class, roleId);
        if (n != null && n > 0) {
            throw new BusinessException(ResultCode.FORBIDDEN, "该角色下仍有账号绑定，请先解除绑定");
        }
    }

    /**
     * 角色被编辑前的前置校验。
     *
     * <p>内置角色（超管）只允许改 `dataScope` 之外展示无害的字段 ——
     * 实际上超管的名称/编码都不允许改，因此这里直接整体拒绝编辑，
     * 由调用方改用「分配菜单权限」接口（超管菜单本就直通全部，无需分配）。
     */
    public void assertRoleEditable(long roleId) {
        if (isBuiltinRole(roleId)) {
            throw new BusinessException(ResultCode.FORBIDDEN,
                    "内置角色（超级管理员）不可编辑，如需调整请使用「分配权限」");
        }
    }

    /** 角色码是否存在（新增/编辑账号绑定角色时校验） */
    public boolean roleCodeExists(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return false;
        }
        Integer n = jdbcTemplate.queryForObject(
                "select count(*) from sys_role where code = ? and deleted = 0", Integer.class, roleCode);
        return n != null && n > 0;
    }

    /** 取账号当前角色码列表 */
    public List<String> roleCodesOf(long userId) {
        return jdbcTemplate.queryForList(
                "select r.code from sys_role r join sys_user_role ur on ur.role_id = r.id "
                        + "where ur.user_id = ? and ur.deleted = 0 and r.deleted = 0",
                String.class, userId);
    }

    /** 账号是否存在且未删除 */
    public Map<String, Object> requireAccount(long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, username, nick_name, status, is_super from sys_user where id = ? and deleted = 0",
                userId);
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "账号不存在");
        }
        return rows.get(0);
    }

    /** 角色是否存在且未删除 */
    public Map<String, Object> requireRole(long roleId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, code, name, is_builtin from sys_role where id = ? and deleted = 0", roleId);
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "角色不存在");
        }
        return new LinkedHashMap<>(rows.get(0));
    }
}
