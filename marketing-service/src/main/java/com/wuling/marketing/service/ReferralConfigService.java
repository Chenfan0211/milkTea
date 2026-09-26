package com.wuling.marketing.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 邀请奖励配置。
 *
 * <p>后台 referral_config 是唯一配置来源；未配置、配置为空或解析失败时使用
 * 与产品当前口径一致的默认值，避免发奖链路因后台数据缺失而中断。
 */
@Service
public class ReferralConfigService {

    private static final Logger log = LoggerFactory.getLogger(ReferralConfigService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReferralConfigService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> getConfig() {
        Map<String, Object> result = defaults();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "select config from referral_config where deleted = 0 order by id limit 1");
            if (rows.isEmpty()) {
                return result;
            }
            Map<String, Object> stored = parse(rows.get(0).get("config"));
            stored.forEach((key, value) -> {
                if (value != null) {
                    result.put(key, value);
                }
            });
        } catch (Exception e) {
            log.warn("读取邀请配置失败，使用默认奖励配置 err={}", e.getMessage());
        }
        return result;
    }

    private Map<String, Object> defaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("firstOrderPoints", 3);
        defaults.put("firstOrderCouponAmount", 3);
        defaults.put("socialStarThreshold", 5);
        defaults.put("socialStarProduct", "");
        defaults.put("recommenderThreshold", 10);
        defaults.put("recommenderRebateRate", 5);
        defaults.put("inviteCodePrefix", "WL");
        return defaults;
    }

    private Map<String, Object> parse(Object raw) throws Exception {
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }

        String json;
        if (raw instanceof byte[] bytes) {
            json = new String(bytes, StandardCharsets.UTF_8);
        } else {
            json = String.valueOf(raw);
        }
        if (json.isBlank()) {
            return Map.of();
        }

        JsonNode node = objectMapper.readTree(json);
        if (node.isTextual()) {
            node = objectMapper.readTree(node.asText());
        }
        if (!node.isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, MAP_TYPE);
    }
}
