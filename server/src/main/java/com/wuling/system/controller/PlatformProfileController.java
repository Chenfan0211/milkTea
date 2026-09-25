package com.wuling.system.controller;

import com.wuling.common.api.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台主体配置接口（AppID / AppSecret / 微信支付商户号）。
 *
 * 存储：platform_profile 表（subject_id = 1 的平台档案），
 *   - app_id / app_secret 为原生列；
 *   - 商户号存入 pay_config JSON：{"mchId":"...","mchKey":"..."}（后续可扩展支付密钥）。
 *
 * 安全约定（重要）：
 *   - AppSecret 只写不读：GET 永不返回；PUT 时传空串/缺省则保留旧值；
 *   - 接口挂在 /api/v1/admin/** 下，由 SecurityConfig 的 JWT 链统一鉴权。
 */
@RestController
@RequestMapping("/api/v1/admin/platform")
public class PlatformProfileController {

    private static final long PLATFORM_SUBJECT_ID = 1L;

    private final JdbcTemplate jdbcTemplate;

    public PlatformProfileController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 查询配置（AppSecret 不回显，只返回是否已配置） */
    @GetMapping("/profile")
    public Result<Map<String, Object>> getProfile(@RequestParam(defaultValue = "1") long subjectId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select app_id, pay_config from platform_profile where subject_id = ? and deleted = 0",
                subjectId);
        Map<String, Object> data = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            data.put("appId", null);
            data.put("mchId", null);
            data.put("secretConfigured", false);
            return Result.ok(data);
        }
        Map<String, Object> row = rows.get(0);
        data.put("appId", row.get("app_id"));
        data.put("mchId", extractMchId(row.get("pay_config")));
        data.put("secretConfigured", jdbcTemplate.queryForObject(
                "select count(*) from platform_profile where subject_id = ? and app_secret is not null and app_secret <> ''",
                Long.class, subjectId) > 0);
        return Result.ok(data);
    }

    /** 保存配置：appSecret 为空时不覆盖旧值 */
    @PutMapping("/profile")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> saveProfile(@RequestBody Map<String, Object> payload) {
        long subjectId = toLong(payload.getOrDefault("subjectId", PLATFORM_SUBJECT_ID));
        String appId = trim(payload.get("appId"));
        String appSecret = trim(payload.get("appSecret"));
        String mchId = trim(payload.get("mchId"));

        int updated;
        if (appSecret == null) {
            // 不修改 secret：只更新 app_id 与 pay_config
            updated = jdbcTemplate.update(
                    "update platform_profile set app_id = ?, pay_config = ? "
                            + "where subject_id = ? and deleted = 0",
                    appId, buildPayConfig(mchId, subjectId), subjectId);
        } else {
            updated = jdbcTemplate.update(
                    "update platform_profile set app_id = ?, app_secret = ?, pay_config = ? "
                            + "where subject_id = ? and deleted = 0",
                    appId, appSecret, buildPayConfig(mchId, subjectId), subjectId);
        }
        if (updated == 0) {
            // 无档案则新建（ uk_platform_profile_subject 保证 subject_id 唯一 ）
            jdbcTemplate.update(
                    "insert into platform_profile (subject_id, app_id, app_secret, pay_config) "
                            + "values (?, ?, ?, ?)",
                    subjectId, appId, appSecret == null ? "" : appSecret, buildPayConfig(mchId, subjectId));
        }
        return Result.ok();
    }

    private String extractMchId(Object payConfig) {
        if (payConfig == null) {
            return null;
        }
        String json = payConfig.toString().trim();
        if (json.isEmpty() || "null".equalsIgnoreCase(json)) {
            return null;
        }
        // 轻量提取，避免为单个字段引入 Jackson 反序列化依赖差异：
        // pay_config 形如 {"mchId":"1900000000","mchKey":"..."}
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"mchId\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** 组装 pay_config：仅更新 mchId，保留原有其它键（如 mchKey） */
    private String buildPayConfig(String mchId, long subjectId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select pay_config from platform_profile where subject_id = ? and deleted = 0", subjectId);
        String existing = rows.isEmpty() || rows.get(0).get("pay_config") == null
                ? "{}" : rows.get(0).get("pay_config").toString();
        String merged = java.util.regex.Pattern
                .compile("\"mchId\"\\s*:\\s*\"[^\"]*\"")
                .matcher(existing)
                .replaceFirst(mchId == null ? "\"mchId\": null" : "\"mchId\": \"" + mchId + "\"");
        if (!merged.contains("mchId")) {
            merged = existing.replaceFirst("\\{\\}",
                    mchId == null ? "{\"mchId\": null}" : "{\"mchId\": \"" + mchId + "\"}");
        }
        return merged;
    }

    private String trim(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(value.toString());
    }
}
