package com.wuling.subject.service;

import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LeaderAttributionService {

    private static final String ACTIVE_GRANTS =
            " from user_role_grant g join app_user u on u.id = g.user_id and u.deleted = 0 and u.status = 1 "
                    + "join biz_subject s on s.id = g.subject_id and s.deleted = 0 and s.status = 'active' "
                    + "and s.subject_type = 'LEADER' ";
    private static final String ACTIVE_ROLE =
            "UPPER(g.role_code) = 'LEADER' and g.status = 'active' and g.deleted = 0 ";
    private static final String EFFECTIVE_RELATION =
            " from user_leader r join user_role_grant g on g.user_id = r.leader_user_id "
                    + "and g.subject_id = r.leader_subject_id and " + ACTIVE_ROLE
                    + "join app_user u on u.id = g.user_id and u.deleted = 0 and u.status = 1 "
                    + "join biz_subject s on s.id = g.subject_id and s.deleted = 0 and s.status = 'active' "
                    + "and s.subject_type = 'LEADER' "
                    + "join app_user buyer on buyer.id = r.user_id and buyer.deleted = 0 and buyer.status = 1 "
                    + "where r.deleted = 0 and r.user_id <> r.leader_user_id ";

    private final JdbcTemplate jdbc;

    public LeaderAttributionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> resolve(Long userId) {
        Long ownSubjectId = activeSubject(userId);
        if (ownSubjectId != null) {
            return result(userId, userId, ownSubjectId, true);
        }
        List<Map<String, Object>> existing = jdbc.queryForList(
                "select r.leader_user_id, r.leader_subject_id" + EFFECTIVE_RELATION
                        + "and r.user_id = ? limit 1", userId);
        if (!existing.isEmpty()) {
            var row = existing.get(0);
            return result(userId, ((Number) row.get("leader_user_id")).longValue(),
                    ((Number) row.get("leader_subject_id")).longValue(), false);
        }
        return result(userId, null, null, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> bindShare(Long userId, Long candidateId) {
        return bind(userId, candidateId, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> bindShareFromSharer(Long userId, Long shareUserId) {
        lockUser(userId);
        Map<String, Object> current = resolve(userId);
        if (current.get("leaderUserId") != null || userId.equals(shareUserId)) {
            return current;
        }
        Map<String, Object> sharer = resolve(shareUserId);
        Long candidateId = (Long) sharer.get("leaderUserId");
        Long subjectId = (Long) sharer.get("leaderSubjectId");
        return candidateId == null || candidateId.equals(userId)
                ? current : save(userId, candidateId, subjectId, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> assign(Long userId, Long candidateId) {
        return bind(userId, candidateId, true);
    }

    @Transactional(rollbackFor = Exception.class)
    public void remove(Long userId) {
        lockUser(userId);
        jdbc.update("update user_leader set deleted = 1 where user_id = ? and deleted = 0", userId);
    }

    private Map<String, Object> bind(Long userId, Long candidateId, boolean admin) {
        if (candidateId == null || candidateId <= 0 || candidateId.equals(userId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "无效的团长或用户不能归属自己");
        }
        // 用户行始终存在，首次绑定也能串行；自动、人工归属和解除共用同一把锁。
        lockUser(userId);
        Long subjectId = activeSubject(candidateId);
        if (subjectId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "目标团长不可用");
        }
        if (!admin) {
            Map<String, Object> current = resolve(userId);
            if (current.get("leaderUserId") != null) {
                return current;
            }
        }
        return save(userId, candidateId, subjectId, admin);
    }

    private Map<String, Object> save(Long userId, Long candidateId, Long subjectId, boolean admin) {
        jdbc.update("insert into user_leader (user_id, leader_user_id, leader_subject_id, binding_source, bound_at) "
                        + "values (?, ?, ?, ?, now()) on duplicate key update "
                        + "leader_user_id = values(leader_user_id), leader_subject_id = values(leader_subject_id), "
                        + "binding_source = values(binding_source), bound_at = now(), deleted = 0",
                userId, candidateId, subjectId, admin ? "ADMIN" : "SHARE");
        return result(userId, candidateId, subjectId, false);
    }

    private Long activeSubject(Long userId) {
        List<Long> subjects = jdbc.queryForList(
                "select g.subject_id" + ACTIVE_GRANTS + "where g.user_id = ? and " + ACTIVE_ROLE
                        + "order by g.id limit 1", Long.class, userId);
        return subjects.isEmpty() ? null : subjects.get(0);
    }

    private void lockUser(Long userId) {
        if (jdbc.queryForList("select id from app_user where id = ? and deleted = 0 and status = 1 for update",
                Long.class, userId).isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在或已停用");
        }
    }

    private Map<String, Object> result(Long userId, Long leaderUserId, Long leaderSubjectId, boolean leader) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("leader", leader);
        result.put("leaderUserId", leaderUserId);
        result.put("leaderSubjectId", leaderSubjectId);
        return result;
    }
}
