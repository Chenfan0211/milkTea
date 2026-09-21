package com.wuling.subject.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.subject.entity.BizSubject;
import com.wuling.subject.entity.StoreProfile;
import com.wuling.subject.mapper.BizSubjectMapper;
import com.wuling.subject.mapper.StoreProfileMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主体绑定关系维护（P2 主体管理）。
 * 覆盖：门店↔投资人、渠道(资源方)↔门店、主体↔用户、用户↔业务角色。
 */
@RestController
@RequestMapping("/api/v1/admin/subject/binding")
public class SubjectBindingController {

    private final BizSubjectMapper bizSubjectMapper;
    private final StoreProfileMapper storeProfileMapper;
    private final JdbcTemplate jdbcTemplate;

    public SubjectBindingController(BizSubjectMapper bizSubjectMapper,
                                    StoreProfileMapper storeProfileMapper,
                                    JdbcTemplate jdbcTemplate) {
        this.bizSubjectMapper = bizSubjectMapper;
        this.storeProfileMapper = storeProfileMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---------- 门店 ↔ 投资人（本期单店单投资人） ----------

    @PostMapping("/store/{storeSubjectId}/investor/{investorSubjectId}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> bindInvestor(@PathVariable Long storeSubjectId, @PathVariable Long investorSubjectId) {
        requireSubject(storeSubjectId, "STORE");
        requireSubject(investorSubjectId, "INVESTOR");
        StoreProfile profile = requireProfile(storeSubjectId);
        profile.setInvestorSubjectId(investorSubjectId);
        storeProfileMapper.updateById(profile);
        return Result.ok();
    }

    @DeleteMapping("/store/{storeSubjectId}/investor")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> unbindInvestor(@PathVariable Long storeSubjectId) {
        StoreProfile profile = requireProfile(storeSubjectId);
        profile.setInvestorSubjectId(null);
        storeProfileMapper.updateById(profile);
        return Result.ok();
    }

    // ---------- 渠道(资源方) ↔ 门店 ----------

    @PostMapping("/channel/{channelSubjectId}/store/{storeSubjectId}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> bindChannelStore(@PathVariable Long channelSubjectId, @PathVariable Long storeSubjectId) {
        requireSubject(channelSubjectId, "CHANNEL");
        requireSubject(storeSubjectId, "STORE");
        Long exists = jdbcTemplate.queryForObject(
                "select count(*) from channel_store where channel_subject_id = ? and store_subject_id = ? and deleted = 0",
                Long.class, channelSubjectId, storeSubjectId);
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该渠道已绑定此门店");
        }
        jdbcTemplate.update("insert into channel_store (channel_subject_id, store_subject_id) values (?, ?)",
                channelSubjectId, storeSubjectId);
        return Result.ok();
    }

    @DeleteMapping("/channel/{channelSubjectId}/store/{storeSubjectId}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> unbindChannelStore(@PathVariable Long channelSubjectId, @PathVariable Long storeSubjectId) {
        jdbcTemplate.update("update channel_store set deleted = 1 "
                        + "where channel_subject_id = ? and store_subject_id = ? and deleted = 0",
                channelSubjectId, storeSubjectId);
        return Result.ok();
    }

    // ---------- 主体 ↔ 用户 ----------

    @PostMapping("/subject/{subjectId}/user/{userId}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> bindSubjectUser(@PathVariable Long subjectId, @PathVariable Long userId) {
        BizSubject subject = requireSubject(subjectId, null);
        subject.setBoundUserId(userId);
        bizSubjectMapper.updateById(subject);
        jdbcTemplate.update("update app_user set bound_subject_id = ? where id = ? and deleted = 0", subjectId, userId);
        return Result.ok();
    }

    @DeleteMapping("/subject/{subjectId}/user")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> unbindSubjectUser(@PathVariable Long subjectId) {
        BizSubject subject = requireSubject(subjectId, null);
        Long userId = subject.getBoundUserId();
        subject.setBoundUserId(null);
        bizSubjectMapper.updateById(subject);
        if (userId != null) {
            jdbcTemplate.update("update app_user set bound_subject_id = null where id = ? and deleted = 0", userId);
        }
        return Result.ok();
    }

    // ---------- 用户 ↔ 业务角色 ----------

    @PostMapping("/user/{userId}/role/{roleCode}/subject/{subjectId}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> bindUserRole(@PathVariable Long userId,
                                     @PathVariable String roleCode,
                                     @PathVariable Long subjectId) {
        requireSubject(subjectId, null);
        Long exists = jdbcTemplate.queryForObject(
                "select count(*) from user_role_grant where user_id = ? and role_code = ? and subject_id = ? and deleted = 0",
                Long.class, userId, roleCode, subjectId);
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该用户已拥有此角色");
        }
        jdbcTemplate.update("insert into user_role_grant (user_id, role_code, subject_id, status, grant_time) "
                        + "values (?, ?, ?, 'active', now())",
                userId, roleCode, subjectId);
        jdbcTemplate.update("update app_user set business_role = ?, bound_subject_id = ? where id = ? and deleted = 0",
                roleCode, subjectId, userId);
        return Result.ok();
    }

    @DeleteMapping("/user/{userId}/role/{roleCode}/subject/{subjectId}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> unbindUserRole(@PathVariable Long userId,
                                       @PathVariable String roleCode,
                                       @PathVariable Long subjectId) {
        jdbcTemplate.update("update user_role_grant set deleted = 1 "
                        + "where user_id = ? and role_code = ? and subject_id = ? and deleted = 0",
                userId, roleCode, subjectId);
        jdbcTemplate.update("update app_user set business_role = null, bound_subject_id = null "
                + "where id = ? and deleted = 0", userId);
        return Result.ok();
    }

    // ---------- 角色申请审核 ----------

    @PostMapping("/application/{id}/review")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> reviewApplication(@PathVariable Long id,
                                          @RequestParam boolean approve,
                                          @RequestParam(required = false) String reason,
                                          @RequestParam(required = false) Long subjectId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, user_id, role_type, status from role_application where id = ? and deleted = 0", id);
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "申请不存在");
        }
        Map<String, Object> row = rows.get(0);
        String status = String.valueOf(row.get("status"));
        if (!"PENDING".equalsIgnoreCase(status)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该申请已处理，不能重复审核");
        }
        jdbcTemplate.update("update role_application set status = ?, review_time = now(), reviewer = ? "
                        + "where id = ?",
                approve ? "APPROVED" : "REJECTED", reason, id);

        if (approve && subjectId != null) {
            Long userId = ((Number) row.get("user_id")).longValue();
            String roleType = String.valueOf(row.get("role_type")).toLowerCase();
            jdbcTemplate.update("update role_application set subject_id = ? where id = ?", subjectId, id);
            jdbcTemplate.update("insert into user_role_grant (user_id, role_code, subject_id, status, grant_time) "
                            + "values (?, ?, ?, 'active', now())",
                    userId, roleType, subjectId);
            jdbcTemplate.update("update app_user set business_role = ?, bound_subject_id = ? where id = ? and deleted = 0",
                    roleType, subjectId, userId);
        }
        return Result.ok();
    }

    @GetMapping("/applications")
    public Result<List<Map<String, Object>>> applications(@RequestParam(required = false) String status) {
        String sql = "select * from role_application where deleted = 0";
        List<Object> args = new java.util.ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql += " and status = ?";
            args.add(status.toUpperCase());
        }
        sql += " order by id desc";
        return Result.ok(jdbcTemplate.queryForList(sql, args.toArray()));
    }

    // ---------- 主体用量概览（仪表盘用） ----------

    @GetMapping("/summary")
    public Result<Map<String, Object>> summary() {
        Map<String, Object> result = new HashMap<>();
        result.put("subjects", count("select count(*) from biz_subject where deleted = 0"));
        result.put("stores", count("select count(*) from biz_subject where subject_type='STORE' and deleted = 0"));
        result.put("channels", count("select count(*) from biz_subject where subject_type='CHANNEL' and deleted = 0"));
        result.put("investors", count("select count(*) from biz_subject where subject_type='INVESTOR' and deleted = 0"));
        result.put("suppliers", count("select count(*) from biz_subject where subject_type='SUPPLIER' and deleted = 0"));
        result.put("users", count("select count(*) from app_user where deleted = 0"));
        result.put("pendingApplications", count("select count(*) from role_application where status='PENDING' and deleted = 0"));
        return Result.ok(result);
    }

    @GetMapping("/list/{type}")
    public Result<List<BizSubject>> listByType(@PathVariable String type) {
        return Result.ok(bizSubjectMapper.selectList(new LambdaQueryWrapper<BizSubject>()
                .eq(BizSubject::getSubjectType, type.toUpperCase())
                .orderByAsc(BizSubject::getId)));
    }

    // ---------- 内部工具 ----------

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private BizSubject requireSubject(Long id, String expectedType) {
        BizSubject subject = bizSubjectMapper.selectById(id);
        if (subject == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "主体不存在: " + id);
        }
        if (expectedType != null && !expectedType.equals(subject.getSubjectType())) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "主体类型不匹配，期望 " + expectedType + "，实际 " + subject.getSubjectType());
        }
        return subject;
    }

    private StoreProfile requireProfile(Long storeSubjectId) {
        StoreProfile profile = storeProfileMapper.selectOne(new LambdaQueryWrapper<StoreProfile>()
                .eq(StoreProfile::getSubjectId, storeSubjectId));
        if (profile == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "门店档案不存在: " + storeSubjectId);
        }
        return profile;
    }
}
