package com.wuling.system.controller;

import com.wuling.common.api.Result;
import com.wuling.common.security.SensitiveCrypto;
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
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public PlatformProfileController(JdbcTemplate jdbcTemplate,
                                     com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
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
            data.put("yeepay", emptyYeepayView());
            return Result.ok(data);
        }
        Map<String, Object> row = rows.get(0);
        data.put("appId", row.get("app_id"));
        data.put("mchId", extractMchId(row.get("pay_config")));
        data.put("secretConfigured", jdbcTemplate.queryForObject(
                "select count(*) from platform_profile where subject_id = ? and app_secret is not null and app_secret <> ''",
                Long.class, subjectId) > 0);
        data.put("yeepay", readYeepayView(row.get("pay_config")));
        return Result.ok(data);
    }

    /**
     * 易宝支付配置（只读视图）。
     *
     * <p><b>私钥永不回显</b>：与 AppSecret 同一约定，只返回
     * {@code privateKeyConfigured} 布尔值。否则任何有后台权限的人
     * 都能导出私钥，等于泄露商户身份。
     */
    @GetMapping("/yeepay")
    public Result<Map<String, Object>> getYeepayConfig(
            @RequestParam(defaultValue = "1") long subjectId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select pay_config from platform_profile where subject_id = ? and deleted = 0",
                subjectId);
        Object payConfig = rows.isEmpty() ? null : rows.get(0).get("pay_config");
        return Result.ok(readYeepayView(payConfig));
    }

    /**
     * 保存易宝支付配置。
     *
     * <p><b>私钥加密存储</b>：入参是明文私钥，入库前用
     * {@link SensitiveCrypto} 加密（AES-256-GCM）。明文私钥泄露
     * 等于他人可冒充本平台发起支付与提现，属最高敏感级别。
     *
     * <p><b>私钥留空表示不修改</b>：与 AppSecret 一致的处理——
     * 运营只想改商编时，不必重新粘贴私钥。
     */
    @PutMapping("/yeepay")
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> saveYeepayConfig(@RequestBody Map<String, Object> payload) {
        long subjectId = toLong(payload.getOrDefault("subjectId", PLATFORM_SUBJECT_ID));

        Map<String, String> updates = new LinkedHashMap<>();
        putIfPresent(updates, "yeepayAppKey", trim(payload.get("appKey")));
        putIfPresent(updates, "yeepayMerchantNo", trim(payload.get("merchantNo")));
        putIfPresent(updates, "yeepayPlatformMerchantNo", trim(payload.get("platformMerchantNo")));
        putIfPresent(updates, "yeepayYopPublicKey", trim(payload.get("yopPublicKey")));

        // 私钥：仅在传入时更新，且必须加密后落库
        String privateKey = trim(payload.get("privateKey"));
        if (privateKey != null) {
            if (!SensitiveCrypto.isEnabled()) {
                throw new com.wuling.common.exception.BusinessException(
                        com.wuling.common.api.ResultCode.ERROR,
                        "敏感字段加密未配置（APP_SENSITIVE_KEY），拒绝明文存储商户私钥");
            }
            updates.put("yeepayPrivateKey", SensitiveCrypto.encrypt(normalizePrivateKey(privateKey)));
        }

        if (updates.isEmpty()) {
            return Result.ok(readYeepayView(loadPayConfig(subjectId)));
        }

        String merged = mergePayConfig(subjectId, updates);
        upsertPayConfig(subjectId, merged);
        return Result.ok(readYeepayView(merged));
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

    // ============================================================
    // 易宝配置辅助方法
    // ============================================================

    /** 读取 pay_config 原始字符串。 */
    private String loadPayConfig(long subjectId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select pay_config from platform_profile where subject_id = ? and deleted = 0",
                subjectId);
        if (rows.isEmpty() || rows.get(0).get("pay_config") == null) {
            return "{}";
        }
        String s = rows.get(0).get("pay_config").toString().trim();
        return s.isEmpty() || "null".equalsIgnoreCase(s) ? "{}" : s;
    }

    /** 写入 pay_config（无档案则新建）。 */
    private void upsertPayConfig(long subjectId, String merged) {
        int updated = jdbcTemplate.update(
                "update platform_profile set pay_config = ? where subject_id = ? and deleted = 0",
                merged, subjectId);
        if (updated == 0) {
            jdbcTemplate.update(
                    "insert into platform_profile (subject_id, app_id, app_secret, pay_config) "
                            + "values (?, null, '', ?)",
                    subjectId, merged);
        }
    }

    /**
     * 合并 pay_config：只覆盖传入的键，保留其余键。
     *
     * <p>用 Jackson 而非正则：JSON 结构一旦复杂（嵌套、转义），
     * 正则替换极易产生非法 JSON 或误伤相似键名。
     */
    private String mergePayConfig(long subjectId, Map<String, String> updates) {
        com.fasterxml.jackson.databind.node.ObjectNode node;
        try {
            node = (com.fasterxml.jackson.databind.node.ObjectNode)
                    objectMapper.readTree(loadPayConfig(subjectId));
        } catch (Exception e) {
            node = objectMapper.createObjectNode();
        }
        updates.forEach(node::put);
        return node.toString();
    }

    /**
     * 易宝配置只读视图（私钥隐藏）。
     *
     * <p>返回结构：appKey / merchantNo / platformMerchantNo / yopPublicKey
     * + {@code privateKeyConfigured} 布尔值。
     */
    private Map<String, Object> readYeepayView(Object payConfig) {
        Map<String, Object> view = new LinkedHashMap<>();
        if (payConfig == null) {
            return emptyYeepayView();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(payConfig.toString());
            view.put("appKey", text(node, "yeepayAppKey"));
            view.put("merchantNo", text(node, "yeepayMerchantNo"));
            view.put("platformMerchantNo", text(node, "yeepayPlatformMerchantNo"));
            view.put("yopPublicKey", text(node, "yeepayYopPublicKey"));
            String enc = text(node, "yeepayPrivateKey");
            // 只暴露「是否已配置」，绝不返回私钥本身（加密的也不行，
            // 避免离线爆破与误用）
            view.put("privateKeyConfigured", enc != null && !enc.isBlank());
        } catch (Exception e) {
            return emptyYeepayView();
        }
        return view;
    }

    private Map<String, Object> emptyYeepayView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("appKey", null);
        view.put("merchantNo", null);
        view.put("platformMerchantNo", null);
        view.put("yopPublicKey", null);
        view.put("privateKeyConfigured", false);
        return view;
    }

    private String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        com.fasterxml.jackson.databind.JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private void putIfPresent(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    /**
     * 规范化私钥：去掉 PEM 头尾与所有空白。
     *
     * <p>运营从易宝后台复制的私钥通常带 {@code -----BEGIN PRIVATE KEY-----}
     * 头尾和换行。SDK 的 {@code RSAKeyUtils.string2PrivateKey} 期望纯 Base64，
     * 带格式会解析失败。这里统一清洗，避免运营反复试。
     */
    private String normalizePrivateKey(String raw) {
        return raw.replaceAll("-----[A-Z ]+-----", "")
                .replaceAll("\\s+", "");
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
