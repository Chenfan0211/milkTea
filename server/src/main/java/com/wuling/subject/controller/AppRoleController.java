package com.wuling.subject.controller;

import com.wuling.common.api.Result;
import com.wuling.security.CurrentUser;
import com.wuling.subject.entity.BizSubject;
import com.wuling.subject.mapper.BizSubjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 小程序端：当前用户的经营角色与绑定主体。
 *
 * <p>前端经营角色体系（role-center / role-workbench / role-income ...）需要知道
 * 「当前登录用户对应哪些经营主体（门店 / 投资人 / 资源方）」，本接口提供该映射，
 * 替代此前前端写死的演示数据（user-h5/data/role-mock.js）。
 *
 * <p>数据来源（均按 JWT 的 userId 过滤）：
 * <ul>
 *   <li>{@code app_user.bound_subject_id} / {@code business_role}：用户当前绑定；</li>
 *   <li>{@code user_role_grant}：用户获授的全部角色（一个用户可能身兼多角色）。</li>
 * </ul>
 *
 * <p>为什么用 JdbcTemplate 而非 AppUserMapper：app_user 归属 user-service，
 * server 模块不依赖其 Java 实体，跨模块读表统一走 JdbcTemplate（与
 * SubjectBindingController 的做法一致）。
 */
@RestController
@RequestMapping("/api/v1/app/roles")
public class AppRoleController {

    /** 主体类型 -> 前端角色 id。与 user-h5/data/role-mock.js 的 roleDefinitions 对齐。 */
    private static final Map<String, String> SUBJECT_TYPE_TO_ROLE = Map.of(
            "STORE", "store",
            "INVESTOR", "investor",
            "CHANNEL", "resource"
    );

    /** 低频使用，静态实例足够（仅用于表单明细序列化）。 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final BizSubjectMapper bizSubjectMapper;
    private final JdbcTemplate jdbcTemplate;

    public AppRoleController(BizSubjectMapper bizSubjectMapper, JdbcTemplate jdbcTemplate) {
        this.bizSubjectMapper = bizSubjectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 我的经营角色列表。
     *
     * <pre>
     * {
     *   "boundSubjectId": 101,
     *   "businessRole": "store",
     *   "roles": [ { "roleId":"store", "status":"active", "subjectId":101,
     *                "subjectCode":"ST-1001", "subjectName":"星沙乐运魔方店" } ]
     * }
     * </pre>
     */
    @GetMapping("/mine")
    public Result<Map<String, Object>> mine() {
        Long userId = CurrentUser.require();

        List<Map<String, Object>> users = jdbcTemplate.queryForList(
                "select bound_subject_id, business_role from app_user where id = ? and deleted = 0", userId);

        Long boundSubjectId = null;
        String businessRole = null;
        if (!users.isEmpty()) {
            Map<String, Object> user = users.get(0);
            Object bound = user.get("bound_subject_id");
            boundSubjectId = bound == null ? null : ((Number) bound).longValue();
            Object role = user.get("business_role");
            businessRole = role == null ? null : String.valueOf(role);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("boundSubjectId", boundSubjectId);
        result.put("businessRole", businessRole);

        List<Map<String, Object>> roles = new ArrayList<>();

        // 1) 当前绑定主体（绑定即视为 active）
        if (boundSubjectId != null) {
            BizSubject subject = bizSubjectMapper.selectById(boundSubjectId);
            String roleId = roleIdOf(subject == null ? null : subject.getSubjectType());
            if (roleId != null) {
                roles.add(toRoleEntry(roleId, "active", subject));
            }
        }

        // 2) 授权表里的全部角色（去重，避免与绑定关系重复）
        List<Map<String, Object>> grants = jdbcTemplate.queryForList(
                "select role_code, subject_id, status from user_role_grant "
                        + "where user_id = ? and deleted = 0 order by id", userId);
        for (Map<String, Object> grant : grants) {
            Object roleCode = grant.get("role_code");
            Object subjectIdValue = grant.get("subject_id");
            Long subjectId = subjectIdValue == null ? null : ((Number) subjectIdValue).longValue();
            String roleId = roleIdOf(String.valueOf(roleCode == null ? "" : roleCode));
            if (roleId == null) {
                continue;
            }
            boolean exists = roles.stream().anyMatch(item -> roleId.equals(item.get("roleId")));
            if (exists) {
                continue;
            }
            BizSubject subject = subjectId == null ? null : bizSubjectMapper.selectById(subjectId);
            String status = "active".equalsIgnoreCase(String.valueOf(grant.get("status"))) ? "active" : "pending";
            roles.add(toRoleEntry(roleId, status, subject));
        }

        result.put("roles", roles);
        return Result.ok(result);
    }

    /**
     * 提交经营角色申请。
     *
     * <p>幂等：同一用户对同一角色若已有 PENDING 或 APPROVED 记录，拒绝重复提交。
     * 与后台审核接口（SubjectBindingController#reviewApplication）配对：
     * 审核通过后才写入 user_role_grant 与 app_user，前端据此看到角色开通。
     *
     * <p>表单明细落库：applicant_name / applicant_phone 提为独立列（便于按手机号检索），
     * 其余各角色差异化字段（门店名称/地址/类型、投资点位/预算等）序列化为 JSON 存入
     * extra_form 列，运营审核时可看到完整申请信息。
     *
     * <p><b>subjectId（2026-09-25 新增支持）</b>：投资人「点位投资申请」等场景，
     * 申请目标是一个具体的门店主体。此时前端传 subjectId（目标门店 id），
     * 后端写入 role_application.subject_id，审核时据此建立绑定关系。
     * 判重规则也相应升级：指定了 subjectId 的申请按 (role_type, subject_id) 判重，
     * 未指定 subjectId 的普通角色开通申请仍按 role_type 判重，保证两类场景互不干扰。
     */
    @PostMapping("/apply")
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> apply(@RequestBody Map<String, Object> payload) {
        Long userId = CurrentUser.require();
        Object roleTypeValue = payload == null ? null : payload.get("roleType");
        String roleType = roleTypeValue == null ? "" : String.valueOf(roleTypeValue).trim().toLowerCase();
        if (roleIdOf2(roleType) == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的角色类型: " + roleType);
        }

        // 目标主体（投资申请=目标门店）；普通角色开通申请可不传
        Long subjectId = payload == null ? null : asLong(payload.get("subjectId"));

        Long exists;
        if (subjectId != null) {
            exists = jdbcTemplate.queryForObject(
                    "select count(*) from role_application where user_id = ? and role_type = ? "
                            + "and subject_id = ? and status in ('PENDING','APPROVED') and deleted = 0",
                    Long.class, userId, roleType, subjectId);
        } else {
            exists = jdbcTemplate.queryForObject(
                    "select count(*) from role_application where user_id = ? and role_type = ? "
                            + "and subject_id is null and status in ('PENDING','APPROVED') and deleted = 0",
                    Long.class, userId, roleType);
        }
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该角色已申请，请勿重复提交");
        }

        // 姓名 / 手机号提为独立列；其余字段整体序列化为 JSON 存入 extra_form
        String applicantName = strOf(payload.get("name"));
        String applicantPhone = strOf(payload.get("phone"));
        String extraForm = toJson(payload);

        if (subjectId != null) {
            jdbcTemplate.update(
                    "insert into role_application "
                            + "(user_id, role_type, subject_id, applicant_name, applicant_phone, extra_form, status, apply_time) "
                            + "values (?, ?, ?, ?, ?, ?, 'PENDING', now())",
                    userId, roleType, subjectId, applicantName, applicantPhone, extraForm);
        } else {
            jdbcTemplate.update(
                    "insert into role_application "
                            + "(user_id, role_type, applicant_name, applicant_phone, extra_form, status, apply_time) "
                            + "values (?, ?, ?, ?, ?, 'PENDING', now())",
                    userId, roleType, applicantName, applicantPhone, extraForm);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roleType", roleType);
        result.put("status", "pending");
        if (subjectId != null) {
            result.put("subjectId", subjectId);
        }
        return Result.ok(result);
    }

    /** 我的角色申请记录（按用户维度）。 */
    @GetMapping("/applications")
    public Result<List<Map<String, Object>>> myApplications() {
        Long userId = CurrentUser.require();
        return Result.ok(jdbcTemplate.queryForList(
                "select id, role_type, applicant_name, applicant_phone, extra_form, "
                        + "status, apply_time, review_time, subject_id "
                        + "from role_application where user_id = ? and deleted = 0 order by id desc",
                userId));
    }

    /** 取字符串字段值；null 安全。 */
    private String strOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 取长整型字段值；null / 空串 / 非法值返回 null。 */
    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        String s = value.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 表单明细序列化为 JSON 字符串；失败时返回 null（不阻断申请提交）。 */
    private String toJson(Map<String, Object> payload) {
        try {
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            return null;
        }
    }

    /** 校验前端角色 id 是否合法。 */
    private String roleIdOf2(String roleId) {
        if (roleId == null) {
            return null;
        }
        return SUBJECT_TYPE_TO_ROLE.containsValue(roleId.toLowerCase()) ? roleId.toLowerCase() : null;
    }

    /** 主体类型 -> 前端角色 id；未知类型返回 null（不上报给前端）。 */
    private String roleIdOf(String subjectType) {
        if (subjectType == null) {
            return null;
        }
        return SUBJECT_TYPE_TO_ROLE.get(subjectType.toUpperCase());
    }

    private Map<String, Object> toRoleEntry(String roleId, String status, BizSubject subject) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("roleId", roleId);
        entry.put("status", status);
        entry.put("subjectId", subject == null ? null : subject.getId());
        entry.put("subjectCode", subject == null ? null : subject.getCode());
        entry.put("subjectName", subject == null ? null : subject.getName());
        return entry;
    }
}
