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

import java.time.format.DateTimeFormatter;
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
        // 唯一键 uk_channel_store(channel_subject_id, store_subject_id) 不含 deleted，
        // 解绑为逻辑删除（deleted=1），历史行仍在表中。直接 insert 会撞唯一键报 500
        // （现象：解绑后再次绑定同一门店必失败）。故先尝试复活历史行，无历史行才插入。
        int revived = jdbcTemplate.update("update channel_store set deleted = 0, create_time = now() "
                        + "where channel_subject_id = ? and store_subject_id = ? and deleted = 1",
                channelSubjectId, storeSubjectId);
        if (revived == 0) {
            jdbcTemplate.update("insert into channel_store (channel_subject_id, store_subject_id) values (?, ?)",
                    channelSubjectId, storeSubjectId);
        }
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

    /**
     * 查询某渠道(资源方)已绑定的门店列表。
     *
     * <p>供「绑定门店数」查看弹窗、解绑门店表格使用。
     * 直接查库返回门店明细，不依赖前端 subjects 镜像，避免镜像未加载时分页数据缺失。
     */
    @GetMapping("/channel/{channelSubjectId}/stores")
    public Result<List<Map<String, Object>>> channelStores(@PathVariable Long channelSubjectId) {
        requireSubject(channelSubjectId, "CHANNEL");
        // 显式映射为驼峰，避免 queryForList 直出下划线列名（create_time）导致前端取不到值
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select s.id, s.code, s.name, sp.city, sp.address, sp.business_status, cs.create_time "
                        + "from channel_store cs "
                        + "join biz_subject s on s.id = cs.store_subject_id and s.deleted = 0 "
                        + "left join store_profile sp on sp.subject_id = s.id and sp.deleted = 0 "
                        + "where cs.channel_subject_id = ? and cs.deleted = 0 "
                        + "order by cs.id desc", channelSubjectId);
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("code", r.get("code"));
            m.put("name", r.get("name"));
            m.put("city", r.get("city"));
            m.put("address", r.get("address"));
            m.put("businessStatus", r.get("business_status"));
            Object ct = r.get("create_time");
            m.put("createTime", ct == null ? null : toDateTimeText(ct));
            result.add(m);
        }
        return Result.ok(result);
    }

    /**
     * 批量解绑门店。
     *
     * <p>单次事务内完成，避免前端循环调用单个删除接口导致的 N 次请求与半成功状态。
     */
    @DeleteMapping("/channel/{channelSubjectId}/stores")
    @Transactional(rollbackFor = Exception.class)
    public Result<Integer> unbindChannelStores(@PathVariable Long channelSubjectId,
                                               @RequestBody Map<String, List<Long>> payload) {
        requireSubject(channelSubjectId, "CHANNEL");
        List<Long> storeIds = payload == null ? null : payload.get("storeIds");
        if (storeIds == null || storeIds.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请选择要解绑的门店");
        }
        int total = 0;
        for (Long storeId : storeIds) {
            total += jdbcTemplate.update("update channel_store set deleted = 1 "
                            + "where channel_subject_id = ? and store_subject_id = ? and deleted = 0",
                    channelSubjectId, storeId);
        }
        return Result.ok(total);
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
        // 反向维护主体侧绑定关系。
        // app_user.bound_subject_id 与 biz_subject.bound_user_id 是同一关系的两个方向，
        // 原先只写前者，导致主体列表读 bound_user_id 恒为 null：
        // 前端会误判为「未绑定」，而后台「代发起提现」「绑定/解绑用户」等功能均无法正常工作。
        jdbcTemplate.update("update biz_subject set bound_user_id = ? where id = ? and deleted = 0",
                userId, subjectId);
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
        // 同步清空主体侧绑定，保持双向一致（与 bindUserRole 对应）
        jdbcTemplate.update("update biz_subject set bound_user_id = null "
                + "where bound_user_id = ? and id = ? and deleted = 0", userId, subjectId);
        return Result.ok();
    }

    // ---------- 角色申请审核 ----------

    /**
     * 审核角色开通申请。
     *
     * <p>普通角色（门店/渠道/供应商）：subjectId 为「用户要绑定的主体」，审核通过后
     * 写 user_role_grant + app_user.bound_subject_id（用户绑定到该主体）。
     *
     * <p><b>投资人投资门店（2026-09-25 新增语义）</b>：role_type=investor 的申请，
     * subject_id 存的是「目标门店 id」。审核通过后不应把用户绑定到门店（用户已是投资人，
     * 已绑定投资人主体），而应建立「门店 ↔ 投资人主体」的绑定：
     *   store_profile.investor_subject_id = 申请人的投资人主体 id。
     * 投资人主体 id 从 user_role_grant 里 role_code=investor 的 subject_id 取。
     */
    @PostMapping("/application/{id}/review")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> reviewApplication(@PathVariable Long id,
                                          @RequestParam boolean approve,
                                          @RequestParam(required = false) String reason,
                                          @RequestParam(required = false) Long subjectId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, user_id, role_type, status, subject_id from role_application where id = ? and deleted = 0", id);
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "申请不存在");
        }
        Map<String, Object> row = rows.get(0);
        String status = String.valueOf(row.get("status"));
        if (!"PENDING".equalsIgnoreCase(status)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该申请已处理，不能重复审核");
        }
        Long userId = ((Number) row.get("user_id")).longValue();
        String roleType = String.valueOf(row.get("role_type")).toLowerCase();
        // 申请时前端已写入的目标主体 id（投资场景=目标门店；普通场景=待绑定的主体）
        Long appliedSubjectId = row.get("subject_id") == null ? null : ((Number) row.get("subject_id")).longValue();

        jdbcTemplate.update("update role_application set status = ?, review_time = now(), reviewer = ? "
                        + "where id = ?",
                approve ? "APPROVED" : "REJECTED", reason, id);

        if (!approve) {
            return Result.ok();
        }

        if ("investor".equals(roleType)) {
            // 投资人投资门店：目标门店 id 来自申请记录（subject_id），审核时可能覆盖
            Long storeSubjectId = subjectId != null ? subjectId : appliedSubjectId;
            if (storeSubjectId == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "投资申请缺少目标门店");
            }
            bindInvestorToStore(userId, storeSubjectId);
            return Result.ok();
        }

        if (subjectId != null) {
            jdbcTemplate.update("update role_application set subject_id = ? where id = ?", subjectId, id);
            jdbcTemplate.update("insert into user_role_grant (user_id, role_code, subject_id, status, grant_time) "
                            + "values (?, ?, ?, 'active', now())",
                    userId, roleType, subjectId);
            jdbcTemplate.update("update app_user set business_role = ?, bound_subject_id = ? where id = ? and deleted = 0",
                    roleType, subjectId, userId);
        }
        return Result.ok();
    }

    /**
     * 投资人投资门店：审核通过后建立「门店 ↔ 投资人主体」绑定。
     *
     * <p>投资人主体 id 从该用户已获授的 investor 角色授权（user_role_grant）取，
     * 若查不到则回退到 app_user.bound_subject_id（business_role=investor）。
     * 写 store_profile.investor_subject_id = 投资人主体 id。
     */
    private void bindInvestorToStore(Long userId, Long storeSubjectId) {
        Long investorSubjectId = jdbcTemplate.queryForList(
                        "select subject_id from user_role_grant "
                                + "where user_id = ? and role_code = 'investor' and status = 'active' and deleted = 0 order by id limit 1",
                        userId)
                .stream()
                .map(m -> m.get("subject_id") == null ? null : ((Number) m.get("subject_id")).longValue())
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (investorSubjectId == null) {
            investorSubjectId = jdbcTemplate.queryForList(
                            "select bound_subject_id from app_user "
                                    + "where id = ? and business_role = 'investor' and deleted = 0",
                            userId)
                    .stream()
                    .map(m -> m.get("bound_subject_id") == null ? null : ((Number) m.get("bound_subject_id")).longValue())
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }
        if (investorSubjectId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "申请人尚未绑定投资人主体，无法建立门店投资绑定");
        }
        jdbcTemplate.update("update store_profile set investor_subject_id = ? "
                        + "where subject_id = ? and deleted = 0",
                investorSubjectId, storeSubjectId);
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

    /** 时间字段统一输出 yyyy-MM-dd HH:mm:ss（与全局 Jackson 配置一致） */
    private String toDateTimeText(Object value) {
        if (value instanceof java.time.LocalDateTime ldt) {
            return ldt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        return String.valueOf(value);
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







