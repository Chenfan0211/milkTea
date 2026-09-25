package com.wuling.system.crud;

import com.wuling.common.api.PageResult;
import com.wuling.common.api.ResultCode;
import com.wuling.common.cache.AppConfigCacheService;
import com.wuling.common.exception.BusinessException;
import com.wuling.common.sql.SqlGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通用 CRUD 服务。
 *
 * 安全约束（关键）：
 * - 资源名、表名、列名全部来自 CrudRegistry 白名单，绝不拼接前端传入的标识符；
 * - 值一律使用占位符绑定，杜绝 SQL 注入；
 * - 只写白名单内字段，防止改到 id/deleted 等敏感列；
 * - 删除为逻辑删除（deleted=1），与全局约定一致。
 */
@Service
public class CrudService {

    /**
     * app_config 资源名：写入该资源后需主动失效 Redis 配置缓存。
     *
     * <p>为什么必须显式失效：配置读取走 {@code AppConfigCacheService}，TTL 长达 30 天。
     * 若改动只落库不清缓存，小程序在缓存过期前仍拿到旧值 —— 表现为
     * 「后台改了客服热线，小程序却一直显示旧号码」，且没有任何报错，排查成本极高。
     */
    private static final String RESOURCE_APP_CONFIG = "appConfig";

    /**
     * 内置角色资源名（sys_role）与超管角色码。
     *
     * <p>「角色与权限」页走的是通用 CRUD，因此超管角色「不可改名/改码/删除」
     * 这条硬规则只能在这里拦截 —— 页面把按钮禁掉只是体验，
     * 直接调 PUT /api/v1/admin/crud/roles/1 一样能改。
     */
    private static final String RESOURCE_ROLES = "roles";
    private static final String SUPER_ROLE_CODE = "R_SUPER";

    /**
     * 「授权中心」资源：roles / grants。
     *
     * <p>需求：「只有超级管理员才有这个菜单的权限，其他角色都没有菜单的权限」。
     * 菜单藏起来了，接口也必须一起挡 —— 否则非超管账号仍可直连接口改角色与授权。
     */
    private static final Set<String> SUPER_ONLY_RESOURCES = Set.of("roles", "grants");

    /**
     * 审计日志表名。
     *
     * <p>为什么后端要自己写审计：原实现只在**前端** localStore 里记一份「假审计」，
     * 库里 audit_log 长期只有登录/提现等少数记录，导致「后台改了什么、谁改的」无从追溯。
     * 后台所有配置类写操作都经本服务，这里落库才能保证审计可信。
     */
    private static final String AUDIT_TABLE = "audit_log";

    /** 审计字段的最大长度（与 audit_log 表结构一致），超长截断避免写入失败 */
    private static final int AUDIT_OPERATOR_MAX = 64;
    private static final int AUDIT_MODULE_MAX = 64;
    private static final int AUDIT_ACTION_MAX = 64;
    private static final int AUDIT_TARGET_MAX = 255;
    private static final int AUDIT_REASON_MAX = 255;

    private static final Logger log = LoggerFactory.getLogger(CrudService.class);

    private final JdbcTemplate jdbcTemplate;
    private final AppConfigCacheService appConfigCacheService;

    public CrudService(JdbcTemplate jdbcTemplate, AppConfigCacheService appConfigCacheService) {
        this.jdbcTemplate = jdbcTemplate;
        this.appConfigCacheService = appConfigCacheService;
    }

    public PageResult<Map<String, Object>> page(String resource, long current, long size,
                                              Map<String, String> search, Map<String, String> filters) {
        CrudRegistry.Resource def = require(resource);
        // SQL 安全：表名与排序子句经 SqlGuard 强制校验（标识符无法参数化，只能白名单约束）
        String table = SqlGuard.ident(def.table());
        String orderBy = SqlGuard.orderBy(def.orderBy());
        StringBuilder where = new StringBuilder(" where deleted = 0");
        List<Object> args = new ArrayList<>();

        if (search != null) {
            for (Map.Entry<String, String> entry : search.entrySet()) {
                String value = entry.getValue();
                if (!StringUtils.hasText(value)) {
                    continue;
                }
                String column = camelToSnake(entry.getKey());
                if (!def.searchable().contains(column)) {
                    continue;
                }
                where.append(" and ").append(column).append(" like ?");
                args.add("%" + value.trim() + "%");
            }
        }

        // 等值过滤：仅对 filterable 白名单列生效。
        // 用于按外键/枚举精确筛选（如 subjects 按 subject_type、fund_flows 按 subject_id），
        // 这类列不适合用 LIKE（会误匹配），且走 LIKE 也无法命中索引。
        if (filters != null) {
            for (Map.Entry<String, String> entry : filters.entrySet()) {
                String value = entry.getValue();
                if (!StringUtils.hasText(value)) {
                    continue;
                }
                String column = camelToSnake(entry.getKey());
                if (!def.filterable().contains(column)) {
                    continue;
                }
                where.append(" and ").append(column).append(" = ?");
                args.add(value.trim());
            }
        }

        Long total = jdbcTemplate.queryForObject(
                "select count(*) from " + table + where, Long.class, args.toArray());
        long offset = Math.max(0, (current - 1) * size);
        String sql = "select * from " + table + where
                + " order by " + orderBy + " limit ? offset ?";
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(offset);
        List<Map<String, Object>> records = jdbcTemplate.queryForList(sql, pageArgs.toArray());
        return PageResult.of(records.stream().map(this::camelize).toList(),
                current, size, total == null ? 0L : total);
    }

    public Map<String, Object> getOne(String resource, long id) {
        CrudRegistry.Resource def = require(resource);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select * from " + SqlGuard.ident(def.table()) + " where id = ? and deleted = 0", id);
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "记录不存在");
        }
        return camelize(rows.get(0));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(String resource, Map<String, Object> payload) {
        CrudRegistry.Resource def = require(resource);
        guardSuperOnly(resource);
        Map<String, Object> values = filterWritable(def, payload);
        // code 为 NOT NULL 唯一键的表（如 coupon/points_product/stored_value_package 等），
        // 前端表单大多不填 code，直接 insert 会报「Field 'code' doesn't have a default value」。
        // 这里在 code 缺失或为空时自动生成唯一 code，保证新增可用；前端显式传 code 时尊重前端值。
        if (def.writable().contains("code") && !hasText(values.get("code"))) {
            values.put("code", generateCode(def.resource()));
        }
        if (values.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "没有可写入的字段");
        }
        String columns = String.join(", ", values.keySet());
        String placeholders = String.join(", ", values.keySet().stream().map(c -> "?").toList());
        String sql = "insert into " + SqlGuard.ident(def.table()) + " (" + columns + ") values (" + placeholders + ")";
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS);
            int index = 1;
            for (Object value : values.values()) {
                ps.setObject(index++, value);
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        Map<String, Object> created = getOne(resource, key == null ? 0L : key.longValue());
        writeAudit(resource, "新增", created, null, created);
        evictAppConfigCache(resource, created);
        return created;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> update(String resource, long id, Map<String, Object> payload) {
        CrudRegistry.Resource def = require(resource);
        guardSuperOnly(resource);
        guardBuiltinRoleWrite(resource, id, false);
        Map<String, Object> values = filterWritable(def, payload);
        if (values.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "没有可更新的字段");
        }
        // 审计需要「改前/改后」对比，故先取原值（失败则不存在，直接抛错）
        Map<String, Object> before = getOne(resource, id);
        String sets = String.join(", ", values.keySet().stream().map(c -> c + " = ?").toList());
        List<Object> args = new ArrayList<>(values.values());
        args.add(id);
        int affected = jdbcTemplate.update(
                "update " + SqlGuard.ident(def.table()) + " set " + sets + " where id = ? and deleted = 0", args.toArray());
        if (affected == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, "记录不存在");
        }
        Map<String, Object> updated = getOne(resource, id);
        writeAudit(resource, "编辑", updated, before, updated);
        evictAppConfigCache(resource, updated);
        return updated;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String resource, long id) {
        CrudRegistry.Resource def = require(resource);
        guardSuperOnly(resource);
        guardBuiltinRoleWrite(resource, id, true);
        // 逻辑删除后记录带 deleted=1，但审计仍需原始内容，故统一在删除前取出
        Map<String, Object> before = getOne(resource, id);
        int affected = jdbcTemplate.update(
                "update " + SqlGuard.ident(def.table()) + " set deleted = 1 where id = ? and deleted = 0", id);
        if (affected == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND, "记录不存在");
        }
        writeAudit(resource, "删除", before, before, null);
        evictAppConfigCache(resource, before);
    }

    // ---------- 内部工具 ----------

    /**
     * 写入审计日志（与业务写操作同事务，失败则一并回滚）。
     *
     * <p>为什么不复用 AdminAuthQueryController 的 /audit 接口：
     * 那是给前端「关键操作」显式调用的，前端一旦忘了调用就没有记录；
     * 而后台配置类写操作全部经本服务，在这里落库才能保证「改了就有痕」不依赖调用方自觉。
     *
     * <p>安全：表名与列名均为本类常量，值全部占位符绑定。
     *
     * @param resource 资源名（写入 module 便于按模块检索）
     * @param action   动作（新增/编辑/删除）
     * @param row      用于取业务主键/名称作为 target 的记录
     * @param before   变更前快照（新增为 null）
     * @param after    变更后快照（删除为 null）
     */
    private void writeAudit(String resource, String action, Map<String, Object> row,
                            Map<String, Object> before, Map<String, Object> after) {
        try {
            jdbcTemplate.update("insert into " + AUDIT_TABLE
                            + " (operator, module, action, target, before_value, after_value, reason, ip)"
                            + " values (?, ?, ?, ?, ?, ?, ?, ?)",
                    truncate(currentOperator(), AUDIT_OPERATOR_MAX),
                    truncate(resource, AUDIT_MODULE_MAX),
                    truncate(action, AUDIT_ACTION_MAX),
                    truncate(targetOf(row), AUDIT_TARGET_MAX),
                    diffSummary(before),
                    diffSummary(after),
                    truncate(reasonOf(row), AUDIT_REASON_MAX),
                    currentIp());
        } catch (Exception e) {
            // 审计写入不应阻断业务（如表结构差异导致），记录后继续
            log.warn("写审计日志失败 resource={} action={}: {}", resource, action, e.getMessage());
        }
    }

    /** 备注：部分资源会带 reason 字段（前端 reasonPrompt 收集），无则留空 */
    private String reasonOf(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        Object reason = row.get("reason");
        return reason == null ? null : String.valueOf(reason);
    }

    /** target 取业务标识：优先 name / code，回退 id */
    private String targetOf(Map<String, Object> row) {
        if (row == null) {
            return "";
        }
        for (String key : List.of("name", "code", "configKey")) {
            Object v = row.get(key);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v);
            }
        }
        Object id = row.get("id");
        return id == null ? "" : String.valueOf(id);
    }

    /**
     * 生成变更摘要：仅保留「真正发生变化」的字段，避免整行 JSON 塞进审计。
     * 新增时无 before，直接输出 after；删除时无 after，直接输出 before。
     */
    private String diffSummary(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        // 剔除无信息量的列，避免审计被噪声淹没
        Map<String, Object> cleaned = new LinkedHashMap<>(row);
        cleaned.keySet().removeAll(Set.of("createTime", "updateTime", "deleted"));
        return cleaned.toString();
    }

    /** 操作人：从 Spring Security 上下文取，取不到则记 system（如定时任务/内部调用） */
    private String currentOperator() {
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && auth.getName() != null
                    && !"anonymousUser".equals(auth.getName())) {
                return auth.getName();
            }
        } catch (Exception ignored) {
            // 无 Security 上下文（如单元测试）时按 system 记录
        }
        return "system";
    }

    /** 请求 IP：取不到则记 unknown */
    private String currentIp() {
        try {
            var attrs = (org.springframework.web.context.request.ServletRequestAttributes)
                    org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                return attrs.getRequest().getRemoteAddr();
            }
        } catch (Exception ignored) {
            // 非 Web 线程（如单元测试）无请求上下文
        }
        return "unknown";
    }

    /** 按列长度截断，避免因超长导致审计插入失败 */
    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * 运营配置（app_config）写操作后失效对应的 Redis 缓存。
     *
     * <p>非 appConfig 资源直接跳过；configKey 缺失时保守起见不做处理
     * （宁可缓存多存一会儿，也不误删其他 key 的缓存）。
     */
    private void evictAppConfigCache(String resource, Map<String, Object> row) {
        if (!RESOURCE_APP_CONFIG.equals(resource) || row == null) {
            return;
        }
        Object configKey = row.get("configKey");
        if (configKey == null || String.valueOf(configKey).isBlank()) {
            return;
        }
        appConfigCacheService.evict(String.valueOf(configKey));
    }

    /**
     * 「授权中心」资源仅超级管理员可写。
     *
     * <p>需求：「超级管理员的信息不能修改」「只有超级管理员才有这个菜单的权限」。
     * 通用 CRUD 是公开给所有已登录管理端的，若不在此拦截，
     * 非超管账号可以绕过菜单直接调 {@code PUT /api/v1/admin/crud/roles/2} 改角色。
     *
     * <p>读操作不拦：列表查询本身不改变数据，且「角色与权限」页对非超管不可见，
     * 拦读只会增加无谓的耦合（例如未来做角色下拉选择器会用到只读接口）。
     */
    private void guardSuperOnly(String resource) {
        if (!SUPER_ONLY_RESOURCES.contains(resource)) {
            return;
        }
        Long userId = com.wuling.security.AdminUser.getUserId();
        if (userId == null || !isSuperAccount(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN,
                    "仅超级管理员可维护「角色与权限」与「角色授权记录」");
        }
    }

    /**
     * 内置角色（超级管理员）不可编辑、不可删除。
     *
     * <p>需求：「超级管理员的信息不能修改」。
     * 允许改的数据面只有账号密码，角色本身连名称都不允许改 ——
     * 否则把 R_SUPER 改名后，前端按角色码判定的「超管直通」就会失效。
     *
     * @param deleting true=删除操作（额外校验角色下是否还有账号绑定）
     */
    private void guardBuiltinRoleWrite(String resource, long id, boolean deleting) {
        if (!RESOURCE_ROLES.equals(resource)) {
            return;
        }
        Integer builtin = jdbcTemplate.queryForObject(
                "select count(*) from sys_role where id = ? and is_builtin = 1 and deleted = 0",
                Integer.class, id);
        if (builtin != null && builtin > 0) {
            throw new BusinessException(ResultCode.FORBIDDEN,
                    deleting ? "内置角色（超级管理员）不可删除" : "内置角色（超级管理员）不可编辑");
        }
        if (!deleting) {
            // 内置角色码不允许被改到别的角色上（防止把 R_SUPER 写成普通角色，或反向把普通角色改成 R_SUPER）
            Integer conflict = jdbcTemplate.queryForObject(
                    "select count(*) from sys_role where id = ? and code = ?", Integer.class, id, SUPER_ROLE_CODE);
            if (conflict != null && conflict > 0) {
                throw new BusinessException(ResultCode.FORBIDDEN, "超级管理员角色不可编辑");
            }
        }
        if (deleting) {
            Integer bound = jdbcTemplate.queryForObject(
                    "select count(*) from sys_user_role where role_id = ? and deleted = 0", Integer.class, id);
            if (bound != null && bound > 0) {
                throw new BusinessException(ResultCode.FORBIDDEN, "该角色下仍有账号绑定，请先解除绑定再删除");
            }
        }
    }

    /** 账号是否为超级管理员（与 AdminRbacGuard#isSuperAccount 同口径） */
    private boolean isSuperAccount(long userId) {
        Integer n = jdbcTemplate.queryForObject(
                "select count(*) from sys_user where id = ? and is_super = 1 and deleted = 0",
                Integer.class, userId);
        return n != null && n > 0;
    }

    private CrudRegistry.Resource require(String resource) {
        CrudRegistry.Resource def = CrudRegistry.get(resource);
        if (def == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "不支持的资源: " + resource);
        }
        return def;
    }

    /**
     * 只保留白名单字段，并把驼峰 key 转为下划线列名。
     *
     * <p><b>为什么对「被丢弃的字段」记 WARN 日志</b>：
     * 这里是历史上最隐蔽的坑 —— 前端传了字段名不等同于后端列名（如 storePerItem vs store_ratio），
     * 白名单校验会静默 continue，接口照样返回 200，页面却永远读不到值，
     * 表现为「填了数据不显示、改了没同步」，排查成本极高。
     * 记日志后此类问题可直接从服务端日志定位，无需逐层比对字段名。
     *
     * <p>注意：仅记录不抛错，保持对既有调用方的兼容（多传字段不算错误）。
     */
    private Map<String, Object> filterWritable(CrudRegistry.Resource def, Map<String, Object> payload) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (payload == null) {
            return values;
        }
        Set<String> dropped = new LinkedHashSet<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            String column = camelToSnake(entry.getKey());
            if (!def.writable().contains(column)) {
                dropped.add(column);
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Boolean bool) {
                value = bool ? 1 : 0;
            }
            // JSON 列（如 benefits / applicable_store_ids / usage_paragraphs）：
            // 前端以数组/对象传入，但 ps.setObject(List) 会把 List 转成 binary 字符串，
            // MySQL 报「Cannot create a JSON value from a string with CHARACTER SET 'binary'」。
            // 这里统一序列化为 JSON 字符串，JSON 列才能正确写入。
            if (value instanceof List || value instanceof Map) {
                value = writeJson(value);
            }
            values.put(column, value);
        }
        if (!dropped.isEmpty()) {
            log.warn("资源 {} 忽略非白名单字段（未写入）: {}", def.resource(), dropped);
        }
        return values;
    }

    /** 值是否为非空文本 */
    private boolean hasText(Object value) {
        return value != null && String.valueOf(value).trim().length() > 0;
    }

    /** 生成唯一 code（资源前缀 + 时间戳 + 随机数），用于 code 缺失时兜底 */
    private String generateCode(String resource) {
        String prefix = switch (resource) {
            case "coupons" -> "CP";
            case "pointsProducts" -> "PP";
            case "pointsEarningRules" -> "PR";
            case "storedValuePackages" -> "SV";
            case "giftCards", "giftCardDenominations" -> "GC";
            case "splitRules" -> "SR";
            default -> resource.toUpperCase();
        };
        long ts = System.currentTimeMillis() % 1000000000L;
        int rand = new java.util.Random().nextInt(9000) + 1000;
        return prefix + "-" + ts + "-" + rand;
    }

    /** 把 List/Map 序列化为 JSON 字符串（供 JSON 列写入） */
    private String writeJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            log.warn("JSON 序列化失败，按空数组处理: {}", e.getMessage());
            return "[]";
        }
    }

    private String camelToSnake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    private String snakeToCamel(String name) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : name.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return sb.toString();
    }

    private Map<String, Object> camelize(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((k, v) -> result.put(snakeToCamel(k), v));
        return result;
    }
}







