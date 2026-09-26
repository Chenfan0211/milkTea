package com.wuling.marketing.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.marketing.entity.PointsSigninRule;
import com.wuling.marketing.mapper.PointsSigninRuleMapper;
import com.wuling.marketing.service.GiftCardAdminService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台营销配置的特殊业务动作（非通用 CRUD）。
 * 覆盖：分账规则启停、签到规则、邀请配置。
 */
@RestController
@RequestMapping("/api/v1/admin/marketing/config")
public class AdminMarketingConfigController {

    private final JdbcTemplate jdbcTemplate;
    private final PointsSigninRuleMapper pointsSigninRuleMapper;
    private final GiftCardAdminService giftCardAdminService;

    public AdminMarketingConfigController(JdbcTemplate jdbcTemplate,
                                          PointsSigninRuleMapper pointsSigninRuleMapper,
                                          GiftCardAdminService giftCardAdminService) {
        this.jdbcTemplate = jdbcTemplate;
        this.pointsSigninRuleMapper = pointsSigninRuleMapper;
        this.giftCardAdminService = giftCardAdminService;
    }

    // ---------- 分账规则启停 ----------

    /**
     * 启用/停用分账规则。
     * 启用时保证全局（GLOBAL）范围下有且仅有一条启用。
     */
    @PostMapping("/split-rule/{id}/toggle")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> toggleSplitRule(@PathVariable Long id, @RequestParam boolean enabled) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, code, scope from split_rule where id = ? and deleted = 0", id);
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "分账规则不存在");
        }
        Map<String, Object> rule = rows.get(0);
        String scope = String.valueOf(rule.get("scope"));

        if (enabled) {
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

    // ---------- 储值套餐（赠券走关联表 + 使用说明，非通用 CRUD 单表能力） ----------

    /**
     * 储值套餐列表（后台视图，含赠送券明细与使用说明）。
     *
     * <p>通用 CRUD 只查 stored_value_package 单表，拿不到赠券（在关联表
     * stored_value_package_coupon）与使用说明（JSON 列）的完整视图，
     * 导致运营后台「赠送券」「使用说明」列恒为空。这里用 JdbcTemplate
     * JOIN 组装，与小程序端 StoredValueService#toDTO 口径一致。
     */
    @GetMapping("/stored-value-packages")
    public Result<PageResult<Map<String, Object>>> storedValuePackages(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size) {
        // 服务端分页：先取总数，再按 limit/offset 只查当前页，避免一次性拉全表。
        // 分页作用在「套餐」主表上，赠送券仍按当前页的 id 逐条补齐（页内数据量小）。
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from stored_value_package where deleted = 0", Long.class);
        long offset = Math.max(0, (Math.max(1, current) - 1) * size);
        List<Map<String, Object>> pkgs = jdbcTemplate.queryForList(
                "select id, code, name, amount, status, usage_paragraphs "
                        + "from stored_value_package where deleted = 0 order by amount asc, id asc limit ? offset ?",
                size, offset);
        List<Map<String, Object>> result = new java.util.ArrayList<>(pkgs.size());
        for (Map<String, Object> pkg : pkgs) {
            Long packageId = ((Number) pkg.get("id")).longValue();
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", pkg.get("id"));
            row.put("code", pkg.get("code"));
            row.put("name", pkg.get("name"));
            row.put("amount", pkg.get("amount"));
            row.put("status", pkg.get("status"));
            row.put("coupons", loadPackageCoupons(packageId));
            row.put("usageParagraphs", parseParagraphs((String) pkg.get("usage_paragraphs")));
            result.add(row);
        }
        return Result.ok(PageResult.of(result, Math.max(1, current), size, total == null ? 0L : total));
    }

    /**
     * 保存某储值套餐的赠送券（全量替换关联表 stored_value_package_coupon）。
     *
     * <p>入参形如 [{"couponId":1,"quantity":2}, ...]。以「先逻辑删除旧行、再插入新行」
     * 实现全量替换，幂等且无唯一键冲突（该表无唯一键，旧行置 deleted=1）。
     */
    @PutMapping("/stored-value/{id}/coupons")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> saveStoredValueCoupons(@PathVariable Long id,
                                               @RequestBody List<Map<String, Object>> coupons) {
        List<Map<String, Object>> pkgs = jdbcTemplate.queryForList(
                "select id from stored_value_package where id = ? and deleted = 0", id);
        if (pkgs.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "储值套餐不存在");
        }
        jdbcTemplate.update("update stored_value_package_coupon set deleted = 1 "
                + "where package_id = ? and deleted = 0", id);
        if (coupons != null) {
            for (Map<String, Object> c : coupons) {
                Long couponId = toLongObject(c.get("couponId"));
                if (couponId == null || couponId <= 0) {
                    continue;
                }
                int quantity = (int) toLong(c.get("quantity"), 1L);
                jdbcTemplate.update("insert into stored_value_package_coupon "
                                + "(package_id, coupon_id, count) values (?, ?, ?)",
                        id, couponId, quantity);
            }
        }
        return Result.ok();
    }

    /**
     * 保存某储值套餐的使用说明（usage_paragraphs JSON 列）。
     *
     * <p>原实现走通用 CRUD 的 usageParagraphs 字段：filterWritable 虽可放行，
     * 但 CrudService 用 ps.setObject(List) 直写 JSON 列会因类型不匹配失败，
     * 故使用说明必须走此专用接口，显式把 List<String> 序列化为 JSON 字符串。
     */
    @PutMapping("/stored-value/{id}/usage")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> saveStoredValueUsage(@PathVariable Long id,
                                             @RequestBody List<String> paragraphs) {
        List<Map<String, Object>> pkgs = jdbcTemplate.queryForList(
                "select id from stored_value_package where id = ? and deleted = 0", id);
        if (pkgs.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "储值套餐不存在");
        }
        String json;
        try {
            json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                    paragraphs == null ? List.of() : paragraphs);
        } catch (Exception e) {
            json = "[]";
        }
        jdbcTemplate.update("update stored_value_package set usage_paragraphs = ? where id = ?", json, id);
        return Result.ok();
    }

    // ---------- 礼品卡（卡面聚合，面额在 gift_card_denomination 表平铺） ----------

    /**
     * 礼品卡「卡面」聚合列表。
     *
     * <p>gift_card_denomination 按「卡种(分组) x 卡面 x 面额」平铺多行
     * （如 gift-001-100/200/500），运营后台应按「卡面」粒度管理：
     * 同一 card_name 的多个 amount 聚合为 faceValues 数组。
     * 通用 CRUD 单表查询无法做该聚合，故此处专用接口按 group_id+card_name 归并。
     */
    @GetMapping("/gift-card-faces")
    public Result<PageResult<Map<String, Object>>> giftCardFaces(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String name) {
        return Result.ok(giftCardAdminService.listFaces(current, size, name));
    }

    @PostMapping("/gift-card-faces")
    public Result<Void> createGiftCardFace(
            @RequestBody GiftCardAdminService.SaveGiftCardFaceRequest request) {
        giftCardAdminService.createFace(request);
        return Result.ok();
    }

    @PutMapping("/gift-card-faces/{faceId}")
    public Result<Void> updateGiftCardFace(
            @PathVariable Long faceId,
            @RequestBody GiftCardAdminService.SaveGiftCardFaceRequest request) {
        giftCardAdminService.updateFace(faceId, request);
        return Result.ok();
    }

    @PostMapping("/gift-card-faces/{faceId}/status")
    public Result<Void> updateGiftCardFaceStatus(
            @PathVariable Long faceId,
            @RequestParam boolean enabled) {
        giftCardAdminService.setFaceStatus(faceId, enabled);
        return Result.ok();
    }

    @GetMapping("/gift-card-groups")
    public Result<List<Map<String, Object>>> giftCardGroups() {
        return Result.ok(giftCardAdminService.listGroups());
    }

    @PostMapping("/gift-card-groups")
    public Result<Map<String, Object>> createGiftCardGroup(
            @RequestBody GiftCardAdminService.GiftCardGroupRequest request) {
        return Result.ok(giftCardAdminService.createGroup(request));
    }

    @PutMapping("/gift-card-groups/{code}")
    public Result<Void> updateGiftCardGroup(
            @PathVariable String code,
            @RequestBody GiftCardAdminService.GiftCardGroupRequest request) {
        giftCardAdminService.updateGroup(code, request);
        return Result.ok();
    }

    @DeleteMapping("/gift-card-groups/{code}")
    public Result<Void> deleteGiftCardGroup(@PathVariable String code) {
        giftCardAdminService.deleteGroup(code);
        return Result.ok();
    }

    // ---------- 内部工具 ----------

    /** 赠送券明细：关联表 JOIN coupon 解析出面额与展示文案 */
    private List<Map<String, Object>> loadPackageCoupons(Long packageId) {
        List<Map<String, Object>> links = jdbcTemplate.queryForList(
                "select c.id as coupon_id, c.name as coupon_name, c.amount as coupon_amount, "
                        + "p.count as quantity "
                        + "from stored_value_package_coupon p "
                        + "join coupon c on c.id = p.coupon_id and c.deleted = 0 "
                        + "where p.package_id = ? and p.deleted = 0 "
                        + "order by p.id asc", packageId);
        List<Map<String, Object>> result = new java.util.ArrayList<>(links.size());
        for (Map<String, Object> link : links) {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("couponId", link.get("coupon_id"));
            item.put("amount", link.get("coupon_amount"));
            item.put("quantity", link.get("quantity"));
            item.put("description", link.get("coupon_name") == null
                    ? "储值赠送券"
                    : link.get("coupon_name"));
            result.add(item);
        }
        return result;
    }

    /** JSON 字符串数组 -> List；解析失败返回空列表（前端回落默认文案） */
    private List<String> parseParagraphs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                    });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 转 Long，null/非法返回 null（用于 couponId 判空跳过） */
    private Long toLongObject(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

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



