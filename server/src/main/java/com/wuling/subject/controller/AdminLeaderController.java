package com.wuling.subject.controller;

import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    public AdminLeaderController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
                "select s.id, s.code, s.name, s.subject_type as subjectType, s.status, "
                        + "(select g.user_id from user_role_grant g where g.subject_id = s.id and UPPER(g.role_code) = 'LEADER' and g.deleted = 0 limit 1) as boundUserId, "
                        + "(select u.nick_name from app_user u join user_role_grant g on g.user_id = u.id where g.subject_id = s.id and UPPER(g.role_code) = 'LEADER' and g.deleted = 0 and u.deleted = 0 limit 1) as boundUserNick, "
                        + "(select u.phone from app_user u join user_role_grant g on g.user_id = u.id where g.subject_id = s.id and UPPER(g.role_code) = 'LEADER' and g.deleted = 0 and u.deleted = 0 limit 1) as boundUserPhone "
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
        if (StringUtils.hasText(status)) {
            sql.append("and s.status = ? ");
            args.add(status.trim());
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
        long userId = ((Number) userIdValue).longValue();
        List<Map<String, Object>> users = jdbcTemplate.queryForList(
                "select id, nick_name from app_user where id = ? and deleted = 0", userId);
        if (users.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户不存在");
        }
        Long exists = jdbcTemplate.queryForObject(
                "select count(*) from user_role_grant where user_id = ? and UPPER(role_code) = 'LEADER' and deleted = 0",
                Long.class, userId);
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该用户已是团长");
        }
        String nick = String.valueOf(users.get(0).get("nick_name"));
        String name = payload.get("name") == null || String.valueOf(payload.get("name")).isBlank()
                ? (StringUtils.hasText(nick) ? nick : "团长" + userId)
                : String.valueOf(payload.get("name")).trim();
        String code = "LD-" + userId;
        jdbcTemplate.update(
                "insert into biz_subject (code, name, subject_type, status) values (?, ?, 'LEADER', 'active') "
                        + "on duplicate key update name = values(name), subject_type = 'LEADER', "
                        + "status = 'active', deleted = 0, bound_user_id = null",
                code, name);
        Long subjectId = jdbcTemplate.queryForObject(
                "select id from biz_subject where code = ? and deleted = 0", Long.class, code);
        if (subjectId == null) {
            throw new BusinessException(ResultCode.ERROR, "团长主体创建失败");
        }
        jdbcTemplate.update(
                "insert into user_role_grant (user_id, role_code, subject_id, status, grant_time) values (?, 'LEADER', ?, 'active', now())",
                userId, subjectId);
        return Result.ok(leaderRow(subjectId));
    }

    // ---------- 解除团长 ----------

    @DeleteMapping("/subject/leaders/{subjectId}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> removeLeader(@PathVariable Long subjectId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select s.id, (select g.user_id from user_role_grant g where g.subject_id = s.id and UPPER(g.role_code) = 'LEADER' and g.deleted = 0 limit 1) as bound_user_id from biz_subject s where s.id = ? and s.subject_type = 'LEADER' and s.deleted = 0",
                subjectId);
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "团长主体不存在");
        }
        Object boundUserId = rows.get(0).get("bound_user_id");
        jdbcTemplate.update("update biz_subject set deleted = 1, bound_user_id = null where id = ?", subjectId);
        if (boundUserId != null) {
            jdbcTemplate.update(
                    "update user_role_grant set deleted = 1 where user_id = ? and UPPER(role_code) = 'LEADER' and subject_id = ?",
                    boundUserId, subjectId);
            jdbcTemplate.update("update user_leader set deleted = 1 where leader_user_id = ?", boundUserId);
        }
        return Result.ok();
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
        long userId = ((Number) userIdValue).longValue();
        long leaderUserId = ((Number) leaderUserIdValue).longValue();
        if (userId == leaderUserId) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户不能归属自己");
        }
        List<Map<String, Object>> leaderRows = jdbcTemplate.queryForList(
                "select subject_id from user_role_grant where user_id = ? and UPPER(role_code) = 'LEADER' "
                        + "and status = 'active' and deleted = 0 order by id limit 1",
                leaderUserId);
        if (leaderRows.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "目标用户不是团长");
        }
        Object leaderSubjectIdValue = leaderRows.get(0).get("subject_id");
        Long leaderSubjectId = leaderSubjectIdValue == null ? null : ((Number) leaderSubjectIdValue).longValue();
        jdbcTemplate.update(
                "insert into user_leader (user_id, leader_user_id, leader_subject_id) values (?, ?, ?) "
                        + "on duplicate key update leader_user_id = values(leader_user_id), "
                        + "leader_subject_id = values(leader_subject_id), deleted = 0",
                userId, leaderUserId, leaderSubjectId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("leaderUserId", leaderUserId);
        result.put("leaderSubjectId", leaderSubjectId);
        return Result.ok(result);
    }

    @DeleteMapping("/user-leader/{userId}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> removeUserLeader(@PathVariable Long userId) {
        jdbcTemplate.update("update user_leader set deleted = 1 where user_id = ? and deleted = 0", userId);
        return Result.ok();
    }

    @GetMapping("/user-leader")
    public Result<PageResult<Map<String, Object>>> userLeaders(@RequestParam(defaultValue = "1") long current,
                                                               @RequestParam(defaultValue = "10") long size,
                                                               @RequestParam(required = false) String search) {
        long safePage = Math.max(1, current);
        long safeSize = Math.min(Math.max(1, size), 100);
        StringBuilder sql = new StringBuilder(
                "select ul.user_id as userId, ul.leader_user_id as leaderUserId, ul.leader_subject_id as leaderSubjectId, "
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
                "select s.id, s.code, s.name, s.subject_type as subjectType, s.status, "
                        + "(select g.user_id from user_role_grant g where g.subject_id = s.id and UPPER(g.role_code) = 'LEADER' and g.deleted = 0 limit 1) as boundUserId, "
                        + "(select u.nick_name from app_user u join user_role_grant g on g.user_id = u.id where g.subject_id = s.id and UPPER(g.role_code) = 'LEADER' and g.deleted = 0 and u.deleted = 0 limit 1) as boundUserNick, "
                        + "(select u.phone from app_user u join user_role_grant g on g.user_id = u.id where g.subject_id = s.id and UPPER(g.role_code) = 'LEADER' and g.deleted = 0 and u.deleted = 0 limit 1) as boundUserPhone "
                        + "from biz_subject s where s.id = ? and s.deleted = 0", subjectId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }
}
