package com.wuling.system.controller;

import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.cache.AppConfigCacheService;
import com.wuling.common.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 小程序端运营配置读取。
 *
 * 配置存于 app_config 表（config_key -> JSON），后台可编辑。
 * 这样首页入口、活动说明、我的页宫格等运营内容无需发版即可调整。
 *
 * 读取链路：Redis 缓存优先，未命中回源 MySQL 并写回缓存
 * （见 {@link AppConfigCacheService}，TTL 1 个月，更新时主动失效）。
 *
 * 安全约定：敏感配置（如 tencent_map_key）不在此对外下发，
 * 统一列入 {@link #SENSITIVE_KEYS}，读取时按不存在处理。
 */
@RestController
@RequestMapping("/api/v1/app/config")
public class AppConfigController {

    /**
     * 敏感配置键：仅允许服务端内部读取（见 GeoCodeService），
     * 任何情况下都不通过小程序端接口返回。
     */
    private static final Set<String> SENSITIVE_KEYS = Set.of("tencent_map_key");

    private final AppConfigCacheService configCacheService;
    private final JdbcTemplate jdbcTemplate;

    public AppConfigController(AppConfigCacheService configCacheService, JdbcTemplate jdbcTemplate) {
        this.configCacheService = configCacheService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按 key 读取单个配置 */
    @GetMapping("/{key}")
    public Result<Object> get(@PathVariable String key) {
        Object value = readPublicValue(key);
        if (value == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "配置不存在: " + key);
        }
        return Result.ok(value);
    }

    /** 一次性读取多项配置（减少小程序请求数） */
    @GetMapping("/batch")
    public Result<Map<String, Object>> batch(@RequestParam String keys) {
        Map<String, Object> result = new HashMap<>();
        for (String key : keys.split(",")) {
            String trimmed = key.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Object value = readPublicValue(trimmed);
            if (value != null) {
                result.put(trimmed, value);
            }
        }
        return Result.ok(result);
    }

    /** 首页所需配置（快捷入口 + 活动） */
    @GetMapping("/home")
    public Result<Map<String, Object>> home() {
        Map<String, Object> result = new HashMap<>();
        result.put("shortcuts", readPublicValue("home_shortcuts"));
        result.put("menuActivity", readPublicValue("menu_activity"));
        return Result.ok(result);
    }

    /** 我的页所需配置 */
    @GetMapping("/profile")
    public Result<Map<String, Object>> profile() {
        Map<String, Object> result = new HashMap<>();
        result.put("functions", readPublicValue("profile_functions"));
        return Result.ok(result);
    }

    /**
     * 城市列表（小程序城市下拉 / city-picker）。
     *
     * <p>数据源改为 {@code activity_city}（运营白名单），只有活动城市才下发；
     * 门店通过 activity_city_id 强绑定。region 作为基础行政区划库保持只读。
     *
     * <p>返回结构：{@code [{id, code, name, parentId, latitude, longitude}]}，
     * id 为 region.id（门店 city_id 关联键）。
     */
    @GetMapping("/cities")
    public Result<Object> cities() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select c.id, c.parent_id, c.code, c.name, c.latitude, c.longitude, p.name as province_name "
                        + "from activity_city ac join region c on c.id = ac.region_id and c.deleted = 0 left join region p on p.id = c.parent_id and p.deleted = 0 "
                        + "where ac.status = 'enabled' and ac.deleted = 0 order by ac.sort asc, c.sort asc, c.id asc");
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("id"));
            item.put("code", row.get("code"));
            item.put("name", row.get("name"));
            item.put("parentId", row.get("parent_id"));
            item.put("provinceName", row.get("province_name"));
            item.put("latitude", row.get("latitude"));
            item.put("longitude", row.get("longitude"));
            out.add(item);
        }
        return Result.ok(out);
    }

    /** 签到规则与奖励 */
    @GetMapping("/signin")
    public Result<Map<String, Object>> signin() {
        Map<String, Object> result = new HashMap<>();
        result.put("rules", readPublicValue("signin_rules"));
        result.put("rewards", readPublicValue("signin_rewards"));
        return Result.ok(result);
    }

    /** 对外读取：敏感键一律视为不存在，避免密钥被接口带出 */
    private Object readPublicValue(String key) {
        if (key == null || SENSITIVE_KEYS.contains(key.trim())) {
            return null;
        }
        return configCacheService.readValue(key);
    }
}
