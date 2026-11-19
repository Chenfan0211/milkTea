package com.wuling.subject.controller;

import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.subject.service.LeaderAttributionService;
import com.wuling.subject.service.SubjectCodes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 团长（LEADER）管理：仅运营后台配置。
 *
 * <p>设计要点：
 * <ul>
 *   <li>团长也是一个用户：为其创建 {@code biz_subject}（subject_type=LEADER）并写
 *       {@code user_role_grant(role_code=LEADER)}，与门店/资源方/投资人等经营角色共存
 *       （user_role_grant 天然支持一用户多角色）；</li>
 *   <li><b>不覆盖</b> {@code app_user.bound_subject_id / business_role}（保留既有主体绑定），
 *       因此团长与自提点/分销商/投资人等主体可同时存在；</li>
 *   <li>团长身份判定与商城分销佣金归属走 {@code user_role_grant} / {@code user_leader}，
 *       不依赖 app_user.business_role。</li>
 * </ul>
 *
 * <p>普通用户 → 团长归属由 {@code user_leader} 表维护（一个用户最多一个团长）。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminLeaderController {

    private final JdbcTemplate jdbcTemplate;

    private final LeaderAttributionService leaderAttribution;

    public AdminLeaderController(JdbcTemplate jdbcTemplate, LeaderAttributionService leaderAttribution) {
        this.jdbcTemplate = jdbcTemplate;
        this.leaderAttribution = leaderAttribution;
    }

    // ---------- 团长列表 ----------

    @GetMapping("/subject/leaders")
    public Result<PageResult<Map<String, Object>>> leaders(@RequestParam(defaultValue = "1") long current,
                                                           @RequestParam(defaultValue = "10") long size,
                                                           @RequestParam(required = false) String search,
                                                           @RequestParam(required = false) String status) {
        long safePage = Math.max(1, current);
        long safeSize = Math.min(Math.max(1, size), 100);
        StringBuilder sql = new StringBuilder(
                "select s.id, s.code, s.name, s.subject_type as subjectType, case when s.status = 'active' then 'active' else 'inactive' end as status, "
                        + "(select g.user_id from user_role_grant g where g.subject_id = s.id and UPPER(g.role_code) = 'LEADER' and g.deleted = 0 limit 1) as boundUserId, "
                        + "(select u.nick_name from app_user u join user_role_grant g on g.user_id = u.id where g.subject_id = s.id and UPPER(g.role_code) = 'LEADER' and g.deleted = 0 and u.deleted = 0 limit 1) as boundUserNick, "
                        + "(select u.phone from app_user u join user_role_grant g on g.user_id = u.id where g.subject_id = s.id and UPPER(g.role_code) = 'LEADER' and g.deleted = 0 and u.deleted = 0 limit 1) as boundUserPhone, "
                        + "(select coalesce(sum(a.available_balance), 0) from subject_account a where a.subject_id = s.id and a.deleted = 0) as accountBalance "
                        + "from biz_subject s where s.subject_type = 'LEADER' and s.deleted = 0 ");
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(search)) {
            sql.append("and (s.name like ? or s.code like ? or exists (select 1 from user_role_grant g where g.subject_id = s.id and g.user_id = ? and UPPER(g.role_code) = 'LEADER' and g.deleted = 0)) ");
            String like = "%" + search.trim() + "%";
            args.add(like);
            args.add(like);
            try {
                args.add(Long.valueOf(search.trim()));
            } catch (NumberFormatException e) {
                args.add(-1L);
            }
        }
        if ("inactive".equalsIgnoreCase(status)) {
            sql.append("and coalesce(s.status, '') <> 'active' ");
        } else if (!"all".equalsIgnoreCase(status)) {
            sql.append("and s.status = 'active' ");
        }
        sql.append("order by s.id asc");
        long total = jdbcTemplate.queryForObject(
                "select count(*) from (" + sql + ") t", Long.class, args.toArray());
        sql.append(" limit ?, ?");
        args.add((safePage - 1) * safeSize);
        args.add(safeSize);
        List<Map<String, Object>> records = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        return Result.ok(PageResult.of(records, safePage, safeSize, total));
    }

    // ---------- 把用户设为团长 ----------

    @PostMapping("/subject/leaders")
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> createLeader(@RequestBody Map<String, Object> payload) {
        Object userIdValue = payload == null ? null : payload.get("userId");
        if (userIdValue == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "userId 不能为空");
        }
        long userId;
        try {
            userId = Long.parseLong(String.valueOf(userIdValue));
        } catch (NumberFormatException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "无效的用户 ID");
        }
        List<Map<String, Object>> users = jdbcTemplate.queryForList(
                "select id, nick_name from app_user where id = ? and status = 1 and deleted = 0 for update", userId);
        if (users.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户不存在");
        }
        Long exists = jdbcTemplate.queryForObject(
                "select count(*) from user_role_grant g join biz_subject s on s.id = g.subject_id "
                        + "where g.user_id = ? and UPPER(g.role_code) = 'LEADER' and g.status = 'active' and g.deleted = 0 "
                        + "and s.subject_type = 'LEADER' and s.status = 'active' and s.deleted = 0",
                Long.class, userId);
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该用户已是团长");
        }
        String nick = (String) users.get(0).get("nick_name");
        String name = payload.get("name") == null || String.valueOf(payload.get("name")).isBlank()
                ? (StringUtils.hasText(nick) ? nick : "团长" + userId)
                : String.valueOf(payload.get("name")).trim();
        List<Long> existingSubjects = jdbcTemplate.queryForList(
                "select g.subject_id from user_role_grant g join biz_subject s on s.id = g.subject_id "
                        + "where g.user_id = ? and UPPER(g.role_code) = 'LEADER' and g.deleted = 0 "
                        + "and s.subject_type = 'LEADER' and s.deleted = 0 order by g.id desc limit 1",
                Long.class, userId);
        Long subjectId;
        if (existingSubjects.isEmpty()) {
            subjectId = insertLeaderSubject(name);
        } else {
            subjectId = existingSubjects.get(0);
            jdbcTemplate.update(
                    "update biz_subject set name = ?, status = 'active', bound_user_id = null "
                            + "where id = ? and deleted = 0",
                    name, subjectId);
        }
        int restored = jdbcTemplate.update(
                "update user_role_grant set status = 'active', grant_time = now() "
                        + "where user_id = ? and subject_id = ? and UPPER(role_code) = 'LEADER' and deleted = 0",
                userId, subjectId);
        if (restored == 0) {
            jdbcTemplate.update(
                    "insert into user_role_grant (user_id, role_code, subject_id, status, grant_time) values (?, 'LEADER', ?, 'active', now())",
                    userId, subjectId);
        }
        return Result.ok(leaderRow(subjectId));
    }

    private long insertLeaderSubject(String name) {
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    "insert into biz_subject (code, name, subject_type, status) values (?, ?, 'LEADER', 'active')",
                    new String[]{"id"});
            ps.setString(1, SubjectCodes.placeholder("LEADER"));
            ps.setString(2, name);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new BusinessException(ResultCode.ERROR, "团长主体创建失败");
        }
        long subjectId = key.longValue();
        jdbcTemplate.update("update biz_subject set code = ? where id = ?",
                SubjectCodes.of("LEADER", subjectId), subjectId);
        return subjectId;
    }

    // ---------- 解除团长 ----------

    @DeleteMapping("/subject/leaders/{subjectId}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> removeLeader(@PathVariable Long subjectId) {
        requireLeaderSubject(subjectId);
        jdbcTemplate.update("update biz_subject set status = 'inactive' where id = ?", subjectId);
        jdbcTemplate.update("update user_role_grant set status = 'inactive' "
                + "where subject_id = ? and UPPER(role_code) = 'LEADER' and deleted = 0", subjectId);
        return Result.ok();
    }

    @GetMapping("/subject/leaders/{subjectId}/deactivate-preview")
    public Result<Map<String, Object>> deactivatePreview(@PathVariable Long subjectId) {
        requireLeaderSubject(subjectId);
        Long bound = jdbcTemplate.queryForObject(
                "select count(*) from user_leader where leader_subject_id = ? and deleted = 0", Long.class, subjectId);
        Long pendingOrders = jdbcTemplate.queryForObject(
                "select count(*) from orders where order_type = 'MALL' and leader_subject_id = ? "
                        + "and status in ('CREATED', 'PAID', 'SHIPPED') and deleted = 0", Long.class, subjectId);
        Long pendingAmount = jdbcTemplate.queryForObject(
                "select coalesce(sum(r.amount), 0) from settlement_record r join orders o on o.id = r.order_id "
                        + "where o.order_type = 'MALL' and r.subject_id = ? and r.status in ('PENDING', 'SETTLEABLE', 'FROZEN') "
                        + "and r.deleted = 0 and o.deleted = 0", Long.class, subjectId);
        return Result.ok(Map.of("boundUserCount", bound, "pendingOrderCount", pendingOrders,
                "pendingSettlementAmount", pendingAmount));
    }

    private void requireLeaderSubject(Long subjectId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from biz_subject where id = ? and subject_type = 'LEADER' and deleted = 0",
                Long.class, subjectId);
        if (count == null || count == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, "团长主体不存在");
        }
    }

    // ---------- 用户归属团长 ----------

    @PostMapping("/user-leader")
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> assignLeader(@RequestBody Map<String, Object> payload) {
        Object userIdValue = payload == null ? null : payload.get("userId");
        Object leaderUserIdValue = payload == null ? null : payload.get("leaderUserId");
        if (userIdValue == null || leaderUserIdValue == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "userId 与 leaderUserId 不能为空");
        }
        try {
            return Result.ok(leaderAttribution.assign(Long.valueOf(String.valueOf(userIdValue)),
                    Long.valueOf(String.valueOf(leaderUserIdValue))));
        } catch (NumberFormatException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "无效的用户或团长 ID");
        }
    }

    @DeleteMapping("/user-leader/{userId}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> removeUserLeader(@PathVariable Long userId) {
        leaderAttribution.remove(userId);
        return Result.ok();
    }

    @GetMapping("/user-leader")
    public Result<PageResult<Map<String, Object>>> userLeaders(@RequestParam(defaultValue = "1") long current,
                                                               @RequestParam(defaultValue = "10") long size,
                                                               @RequestParam(required = false) String search,
                                                               @RequestParam(required = false) Long leaderUserId) {
        long safePage = Math.max(1, current);
        long safeSize = Math.min(Math.max(1, size), 100);
        StringBuilder sql = new StringBuilder(
                "select ul.user_id as userId, ul.leader_user_id as leaderUserId, ul.leader_subject_id as leaderSubjectId, "
                        + "ul.binding_source as bindingSource, ul.bound_at as boundAt, "
                        + "u.nick_name as userNick, u.phone as userPhone, "
                        + "lu.nick_name as leaderNick, lu.phone as leaderPhone "
                        + "from user_leader ul "
                        + "left join app_user u on u.id = ul.user_id and u.deleted = 0 "
                        + "left join app_user lu on lu.id = ul.leader_user_id and lu.deleted = 0 "
                        + "where ul.deleted = 0 ");
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(search)) {
            sql.append("and (u.nick_name like ? or u.phone like ? or ul.user_id = ?) ");
            String like = "%" + search.trim() + "%";
            args.add(like);
            args.add(like);
            try {
                args.add(Long.valueOf(search.trim()));
            } catch (NumberFormatException e) {
                args.add(-1L);
            }
        }
        if (leaderUserId != null) {
            sql.append("and ul.leader_user_id = ? ");
            args.add(leaderUserId);
        }
        sql.append("order by ul.id desc");
        long total = jdbcTemplate.queryForObject(
                "select count(*) from (" + sql + ") t", Long.class, args.toArray());
        sql.append(" limit ?, ?");
        args.add((safePage - 1) * safeSize);
        args.add(safeSize);
        List<Map<String, Object>> records = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        return Result.ok(PageResult.of(records, safePage, safeSize, total));
    }

    // ---------- helpers ----------

    private Map<String, Object> leaderRow(Long subjectId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select s.id, s.code, s.name, s.subject_type as subjectType, case when s.status = 'active' then 'active' else 'inactive' end as status, "
                        + "(select g.user_id from user_role_grant g where g.subject_id = s.id and UPPER(g.role_code) = 'LEADER' and g.deleted = 0 limit 1) as boundUserId, "
                        + "(select u.nick_name from app_user u join user_role_grant g on g.user_id = u.id where g.subject_id = s.id and UPPER(g.role_code) = 'LEADER' and g.deleted = 0 and u.deleted = 0 limit 1) as boundUserNick, "
                        + "(select u.phone from app_user u join user_role_grant g on g.user_id = u.id where g.subject_id = s.id and UPPER(g.role_code) = 'LEADER' and g.deleted = 0 and u.deleted = 0 limit 1) as boundUserPhone "
                        + "from biz_subject s where s.id = ? and s.deleted = 0", subjectId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }
}
