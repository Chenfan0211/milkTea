package com.wuling.marketing.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.marketing.entity.PointsSigninRule;
import com.wuling.marketing.mapper.PointsSigninRuleMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台营销配置的特殊业务动作（非通用 CRUD）。
 * 覆盖：分账规则启停、签到规则、邀请配置、评论审核。
 */
@RestController
@RequestMapping("/api/v1/admin/marketing/config")
public class AdminMarketingConfigController {

    private final JdbcTemplate jdbcTemplate;
    private final PointsSigninRuleMapper pointsSigninRuleMapper;

    public AdminMarketingConfigController(JdbcTemplate jdbcTemplate,
                                          PointsSigninRuleMapper pointsSigninRuleMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.pointsSigninRuleMapper = pointsSigninRuleMapper;
    }

    // ---------- 分账规则启停 ----------

    /**
     * 启用/停用分账规则。
     * 启用时会校验五方比例合计必须为 10000（万分比），并保证同 scope 下只有一条启用。
     */
    @PostMapping("/split-rule/{id}/toggle")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> toggleSplitRule(@PathVariable Long id, @RequestParam boolean enabled) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, code, scope, product_id, "
                        + "(platform_ratio+store_ratio+channel_ratio+investor_ratio+supplier_ratio) as total "
                        + "from split_rule where id = ? and deleted = 0", id);
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "分账规则不存在");
        }
        Map<String, Object> rule = rows.get(0);
        long total = ((Number) rule.get("total")).longValue();
        String scope = String.valueOf(rule.get("scope"));

        if (enabled) {
            if (total != 10000) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "分账比例合计必须为 10000（万分比），当前为 " + total);
            }
            // 同范围只允许一条启用，避免分账时规则歧义
            if ("GLOBAL".equals(scope)) {
                jdbcTemplate.update("update split_rule set status = 'disabled' "
                        + "where scope = 'GLOBAL' and id <> ? and deleted = 0", id);
            }
        }
        jdbcTemplate.update("update split_rule set status = ? where id = ?",
                enabled ? "enabled" : "disabled", id);
        return Result.ok();
    }

    // ---------- 签到规则 ----------

    @GetMapping("/signin-rule")
    public Result<Map<String, Object>> signinRule() {
        PointsSigninRule rule = pointsSigninRuleMapper.selectList(
                new LambdaQueryWrapper<PointsSigninRule>().orderByAsc(PointsSigninRule::getId))
                .stream().findFirst().orElse(null);
        Map<String, Object> result = new HashMap<>();
        result.put("daily", rule == null || rule.getDaily() == null ? 1L : rule.getDaily());
        result.put("streakDays", rule == null || rule.getStreakDays() == null ? 7 : rule.getStreakDays());
        result.put("streakReward", rule == null || rule.getStreakReward() == null ? 20L : rule.getStreakReward());
        result.put("rewards", List.of(Map.of(
                "days", result.get("streakDays"), "amount", result.get("streakReward"))));
        return Result.ok(result);
    }

    @PostMapping("/signin-rule")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> saveSigninRule(@RequestBody Map<String, Object> payload) {
        long daily = toLong(payload.get("daily"), 1L);
        long streakReward = toLong(payload.get("streakReward"), 20L);
        int streakDays = (int) toLong(payload.get("streakDays"), 7L);

        // 兼容前端 rewards 数组形式
        Object rewards = payload.get("rewards");
        if (rewards instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            streakDays = (int) toLong(first.get("days"), streakDays);
            streakReward = toLong(first.get("amount"), streakReward);
        }
        if (daily < 0 || streakReward < 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "奖励值不能为负");
        }

        PointsSigninRule existing = pointsSigninRuleMapper.selectList(
                new LambdaQueryWrapper<PointsSigninRule>().orderByAsc(PointsSigninRule::getId))
                .stream().findFirst().orElse(null);
        if (existing == null) {
            PointsSigninRule rule = new PointsSigninRule();
            rule.setDaily(daily);
            rule.setStreakDays(streakDays);
            rule.setStreakReward(streakReward);
            pointsSigninRuleMapper.insert(rule);
        } else {
            existing.setDaily(daily);
            existing.setStreakDays(streakDays);
            existing.setStreakReward(streakReward);
            pointsSigninRuleMapper.updateById(existing);
        }
        return Result.ok();
    }

    // ---------- 邀请配置 ----------

    @GetMapping("/referral-config")
    public Result<Map<String, Object>> referralConfig() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select config from referral_config where deleted = 0 order by id limit 1");
        Map<String, Object> result = new HashMap<>();
        result.put("id", 1);
        result.put("config", rows.isEmpty() ? null : rows.get(0).get("config"));
        return Result.ok(result);
    }

    @PostMapping("/referral-config")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> saveReferralConfig(@RequestBody Map<String, Object> payload) {
        String json = toJson(payload);
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from referral_config where deleted = 0", Long.class);
        if (count == null || count == 0) {
            jdbcTemplate.update("insert into referral_config (config) values (?)", json);
        } else {
            jdbcTemplate.update("update referral_config set config = ? where deleted = 0 order by id limit 1", json);
        }
        return Result.ok();
    }

    // ---------- 评论审核 ----------

    @PostMapping("/comment/{id}/review")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> reviewComment(@PathVariable Long id,
                                      @RequestParam boolean approve,
                                      @RequestParam(required = false) String reason) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, status from comments where id = ? and deleted = 0", id);
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "评论不存在");
        }
        if (!"PENDING".equalsIgnoreCase(String.valueOf(rows.get(0).get("status")))) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该评论已审核，不能重复操作");
        }
        jdbcTemplate.update("update comments set status = ?, review_time = now() where id = ?",
                approve ? "APPROVED" : "REJECTED", id);
        return Result.ok();
    }

    // ---------- 内部工具 ----------

    private long toLong(Object value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }
}
