package com.wuling.subject.controller;

import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主体档案聚合接口（渠道/资源方、供应商、投资人）。
 *
 * <p>背景：这些主体在 biz_subject 存基础信息，业务字段分散在各自的 profile 子表
 * （channel_profile / supplier_profile / investor_profile）。通用 CRUD 只查 biz_subject
 * 单表，导致「所在地/门店类型/关联商品数/可投门店数」等列永远为空。
 *
 * <p>本控制器提供「列表聚合 + 新增/编辑（写 profile 子表）」：
 *   - GET  /api/v1/admin/subject/channels   渠道(资源方)分页
 *   - POST /api/v1/admin/subject/channels   新增渠道
 *   - PUT  /api/v1/admin/subject/channels/{id} 编辑渠道
 *   - GET/POST/PUT 同理：suppliers、investors
 *
 * <p>必填校验（与前端一致）：渠道的所在地/门店类型、供应商/投资人的名称必填。
 * 删除仍走通用 CRUD（逻辑删除 biz_subject.deleted=1），profile 子表由业务兜底过滤。
 */
@RestController
@RequestMapping("/api/v1/admin/subject")
public class AdminSubjectProfileController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    public AdminSubjectProfileController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ================= 渠道 / 资源方 =================

    @GetMapping("/channels")
    public Result<PageResult<Map<String, Object>>> channels(@RequestParam(defaultValue = "1") long current,
                                                            @RequestParam(defaultValue = "10") long size,
                                                            @RequestParam(required = false) String search,
                                                            @RequestParam(required = false) String status) {
        return Result.ok(pageSubjects("CHANNEL", current, size, search, status));
    }

    @PostMapping("/channels")
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> createChannel(@RequestBody Map<String, Object> payload) {
        long subjectId = insertSubject(payload, "CHANNEL", "active");
        upsertChannelProfile(subjectId, payload);
        return Result.ok(rowById(subjectId, "CHANNEL"));
    }

    @PutMapping("/channels/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> updateChannel(@PathVariable long id, @RequestBody Map<String, Object> payload) {
        requireType(id, "CHANNEL");
        jdbcTemplate.update("update biz_subject set name = ?, status = ? where id = ? and deleted = 0",
                required(payload.get("name"), "名称"), asStr(payload.get("status"), "active"), id);
        upsertChannelProfile(id, payload);
        return Result.ok(rowById(id, "CHANNEL"));
    }

    // ================= 供应商 =================

    @GetMapping("/suppliers")
    public Result<PageResult<Map<String, Object>>> suppliers(@RequestParam(defaultValue = "1") long current,
                                                             @RequestParam(defaultValue = "10") long size,
                                                             @RequestParam(required = false) String search,
                                                             @RequestParam(required = false) String status) {
        return Result.ok(pageSubjects("SUPPLIER", current, size, search, status));
    }

    @PostMapping("/suppliers")
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> createSupplier(@RequestBody Map<String, Object> payload) {
        long subjectId = insertSubject(payload, "SUPPLIER", "active");
        upsertSupplierProfile(subjectId, payload);
        return Result.ok(rowById(subjectId, "SUPPLIER"));
    }

    @PutMapping("/suppliers/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> updateSupplier(@PathVariable long id, @RequestBody Map<String, Object> payload) {
        requireType(id, "SUPPLIER");
        jdbcTemplate.update("update biz_subject set name = ?, status = ? where id = ? and deleted = 0",
                required(payload.get("name"), "名称"), asStr(payload.get("status"), "active"), id);
        upsertSupplierProfile(id, payload);
        return Result.ok(rowById(id, "SUPPLIER"));
    }

    // ================= 投资人 =================

    @GetMapping("/investors")
    public Result<PageResult<Map<String, Object>>> investors(@RequestParam(defaultValue = "1") long current,
                                                             @RequestParam(defaultValue = "10") long size,
                                                             @RequestParam(required = false) String search,
                                                             @RequestParam(required = false) String status) {
        return Result.ok(pageSubjects("INVESTOR", current, size, search, status));
    }

    @PostMapping("/investors")
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> createInvestor(@RequestBody Map<String, Object> payload) {
        long subjectId = insertSubject(payload, "INVESTOR", "signed");
        upsertInvestorProfile(subjectId, payload);
        return Result.ok(rowById(subjectId, "INVESTOR"));
    }

    @PutMapping("/investors/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> updateInvestor(@PathVariable long id, @RequestBody Map<String, Object> payload) {
        requireType(id, "INVESTOR");
        jdbcTemplate.update("update biz_subject set name = ?, status = ? where id = ? and deleted = 0",
                required(payload.get("name"), "姓名"), asStr(payload.get("status"), "signed"), id);
        upsertInvestorProfile(id, payload);
        return Result.ok(rowById(id, "INVESTOR"));
    }

    // ================= 公共实现 =================

    private PageResult<Map<String, Object>> pageSubjects(String type, long current, long size, String search, String status) {
        StringBuilder where = new StringBuilder(" where s.subject_type = ? and s.deleted = 0");
        List<Object> args = new ArrayList<>();
        args.add(type);
        if (StringUtils.hasText(status)) {
            where.append(" and s.status = ?");
            args.add(status.trim());
        }
        if (StringUtils.hasText(search)) {
            where.append(" and (s.code like ? or s.name like ?)");
            String like = "%" + search.trim() + "%";
            args.add(like);
            args.add(like);
        }
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from biz_subject s" + where, Long.class, args.toArray());
        String sql = "select * from biz_subject s" + where + " order by s.id asc limit ? offset ?";
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(Math.max(0, (current - 1) * size));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, pageArgs.toArray());
        List<Map<String, Object>> records = rows.stream()
                .map(r -> toRow(((Number) r.get("id")).longValue(), type))
                .toList();
        return PageResult.of(records, current, size, total == null ? 0L : total);
    }

    private Map<String, Object> rowById(long id, String type) {
        return toRow(id, type);
    }

    private Map<String, Object> toRow(long id, String type) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select * from biz_subject where id = ? and deleted = 0", id);
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "记录不存在");
        }
        Map<String, Object> s = rows.get(0);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", s.get("id"));
        row.put("code", s.get("code"));
        row.put("name", s.get("name"));
        row.put("status", s.get("status"));
        Object boundUserId = s.get("bound_user_id");
        row.put("boundUserId", boundUserId);
        row.put("boundUserName", boundUserId == null ? "未绑定" : "用户" + boundUserId);
        Object ct = s.get("create_time");
        row.put("createTime", ct == null ? null : formatTime(ct));
        row.put("type", type.toLowerCase());

        if ("CHANNEL".equals(type)) {
            Map<String, Object> p = profileOne("channel_profile", id);
            row.put("location", p == null ? null : p.get("location"));
            row.put("storeType", p == null ? null : p.get("store_type"));
            Long boundStoreCount = jdbcTemplate.queryForObject(
                    "select count(*) from channel_store where channel_subject_id = ? and deleted = 0",
                    Long.class, id);
            row.put("boundStoreCount", boundStoreCount == null ? 0 : boundStoreCount.intValue());
            List<Map<String, Object>> channelStores = jdbcTemplate.queryForList(
                    "select store_subject_id from channel_store where channel_subject_id = ? and deleted = 0", id);
            List<Long> channelStoreIds = new ArrayList<>();
            for (Map<String, Object> cs : channelStores) {
                channelStoreIds.add(((Number) cs.get("store_subject_id")).longValue());
            }
            row.put("boundStoreIds", channelStoreIds);
        } else if ("SUPPLIER".equals(type)) {
            Map<String, Object> p = profileOne("supplier_profile", id);
            row.put("productCount", p == null ? 0 : p.get("product_count"));
            // 联系方式组（V37 新增列）：供列表与编辑表单回显
            row.put("contactName", p == null ? null : p.get("contact_name"));
            row.put("phone", p == null ? null : p.get("phone"));
            row.put("address", p == null ? null : p.get("address"));
            row.put("email", p == null ? null : p.get("email"));
            row.put("remark", p == null ? null : p.get("remark"));
        } else if ("INVESTOR".equals(type)) {
            Map<String, Object> p = profileOne("investor_profile", id);
            row.put("investableStoreCount", p == null ? 0 : p.get("investable_store_count"));
            row.put("signStatus", p == null ? null : p.get("sign_status"));
            Long boundStoreCount = jdbcTemplate.queryForObject(
                    "select count(*) from store_profile where investor_subject_id = ? and deleted = 0",
                    Long.class, id);
            row.put("boundStoreCount", boundStoreCount == null ? 0 : boundStoreCount.intValue());
            List<Map<String, Object>> investorStores = jdbcTemplate.queryForList(
                    "select sp.subject_id as sid, s.name as sname from store_profile sp "
                            + "join biz_subject s on s.id = sp.subject_id and s.deleted = 0 "
                            + "where sp.investor_subject_id = ? and sp.deleted = 0", id);
            List<Long> investorStoreIds = new ArrayList<>();
            List<String> investorStoreNames = new ArrayList<>();
            for (Map<String, Object> st : investorStores) {
                investorStoreIds.add(((Number) st.get("sid")).longValue());
                investorStoreNames.add(String.valueOf(st.get("sname")));
            }
            row.put("relatedStoreIds", investorStoreIds);
            row.put("relatedStore", investorStoreNames.isEmpty() ? "未绑定" : String.join("、", investorStoreNames));
        }
        return row;
    }

    private Map<String, Object> profileOne(String table, long subjectId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select * from " + table + " where subject_id = ? and deleted = 0", subjectId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long insertSubject(Map<String, Object> payload, String type, String defaultStatus) {
        String name = required(payload.get("name"), "名称");
        String code = asStr(payload.get("code"), nextCode(type));
        String status = asStr(payload.get("status"), defaultStatus);
        jdbcTemplate.update(
                "insert into biz_subject (code, name, subject_type, status) values (?, ?, ?, ?)",
                code, name, type, status);
        Long id = jdbcTemplate.queryForObject("select max(id) from biz_subject where code = ?", Long.class, code);
        return id == null ? 0L : id;
    }

    private void upsertChannelProfile(long subjectId, Map<String, Object> payload) {
        String location = required(payload.get("location"), "所在地");
        String storeType = required(payload.get("storeType"), "门店类型");
        upsertProfile("channel_profile", subjectId, Map.of("location", location, "store_type", storeType));
    }

    /**
     * 写入供应商档案（含 V37 新增的联系方式组）。
     *
     * <p><b>为什么用 LinkedHashMap 而不是 Map.of</b>：
     * {@code Map.of} 不允许 null 值，而地址/邮箱/备注都是选填 ——
     * 运营留空时传进来就是 null，用 Map.of 会直接抛 NullPointerException。
     * LinkedHashMap 允许 null，且 {@link #upsertProfile} 会用占位符把 null 写成 NULL。
     *
     * <p>必填校验：联系电话（业务要求）。不在这里抛异常，而是交给前端与
     * {@link #required} 统一提示，保证错误信息一致。
     */
    private void upsertSupplierProfile(long subjectId, Map<String, Object> payload) {
        String phone = required(payload.get("phone"), "联系电话");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("product_count", String.valueOf(asInt(payload.get("productCount"), 0)));
        values.put("contact_name", asStr(payload.get("contactName"), null));
        values.put("phone", phone);
        values.put("address", asStr(payload.get("address"), null));
        values.put("email", asStr(payload.get("email"), null));
        values.put("remark", asStr(payload.get("remark"), null));
        upsertProfile("supplier_profile", subjectId, values);
    }

    private void upsertInvestorProfile(long subjectId, Map<String, Object> payload) {
        int investable = asInt(payload.get("investableStoreCount"), 0);
        String signStatus = asStr(payload.get("signStatus"), asStr(payload.get("status"), "signed"));
        upsertProfile("investor_profile", subjectId,
                Map.of("investable_store_count", String.valueOf(investable), "sign_status", signStatus));
    }

    private void upsertProfile(String table, long subjectId, Map<String, String> values) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id from " + table + " where subject_id = ? and deleted = 0", subjectId);
        if (rows.isEmpty()) {
            StringBuilder cols = new StringBuilder("subject_id");
            StringBuilder vals = new StringBuilder("?");
            List<Object> args = new ArrayList<>();
            args.add(subjectId);
            for (Map.Entry<String, String> e : values.entrySet()) {
                cols.append(", ").append(e.getKey());
                vals.append(", ?");
                args.add(e.getValue());
            }
            jdbcTemplate.update("insert into " + table + " (" + cols + ") values (" + vals + ")", args.toArray());
        } else {
            StringBuilder set = new StringBuilder();
            List<Object> args = new ArrayList<>();
            for (Map.Entry<String, String> e : values.entrySet()) {
                if (set.length() > 0) set.append(", ");
                set.append(e.getKey()).append(" = ?");
                args.add(e.getValue());
            }
            args.add(subjectId);
            jdbcTemplate.update("update " + table + " set " + set + " where subject_id = ?", args.toArray());
        }
    }

    private void requireType(long id, String type) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select subject_type from biz_subject where id = ? and deleted = 0", id);
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "记录不存在");
        }
        if (!type.equals(rows.get(0).get("subject_type"))) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "主体类型不匹配");
        }
    }

    private String required(Object value, String label) {
        String s = asStr(value, null);
        if (!StringUtils.hasText(s)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, label + "必填");
        }
        return s.trim();
    }

    private String asStr(Object value, String def) {
        if (value == null) return def;
        String s = value.toString().trim();
        return s.isEmpty() ? def : s;
    }

    private int asInt(Object value, int def) {
        if (value == null) return def;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private String nextCode(String type) {
        String prefix = switch (type) {
            case "CHANNEL" -> "RS";
            case "SUPPLIER" -> "SU";
            case "INVESTOR" -> "IV";
            default -> "SUB";
        };
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from biz_subject where subject_type = ?", Long.class, type);
        return prefix + "-" + String.format("%04d", (count == null ? 0 : count) + 1000);
    }

    private String formatTime(Object value) {
        if (value instanceof LocalDateTime ldt) return ldt.format(FMT);
        if (value instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().format(FMT);
        return value.toString();
    }
}
