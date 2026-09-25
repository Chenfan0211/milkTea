package com.wuling.system.controller;

import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台详情查询（只读，P2 修复「详情页读本地假数据」）。
 *
 * <p>背景：核销记录 / 分账快照 / 角色申请三个详情页原先直接从前端 store 内存数组
 * {@code find()} 取行，不触发任何接口调用。用户直接打开详情 URL（或刷新页面）时，
 * store 里只有 localStorage 缓存的 seed 假数据，导致详情页展示与数据库不符。
 *
 * <p>本控制器按 id 提供单条详情，并 join 出页面需要的关联字段
 * （如核销记录的门店名），避免前端依赖 subjects 镜像。
 * 方案：只读 join，不加冗余列（表结构保持不变）。
 */
@RestController
@RequestMapping("/api/v1/admin/detail")
public class AdminDetailQueryController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    public AdminDetailQueryController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---------- 核销记录详情 ----------

    @GetMapping("/verify/{id}")
    public Result<Map<String, Object>> verifyDetail(@PathVariable Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select v.id, v.verify_code, v.order_no, v.type, v.operator, v.device, v.result, "
                        + "       v.store_subject_id, v.create_time, s.name as store_name "
                        + "  from verify_record v "
                        + "  left join biz_subject s on s.id = v.store_subject_id and s.deleted = 0 "
                        + " where v.id = ? and v.deleted = 0", id);
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "核销记录不存在");
        }
        Map<String, Object> r = rows.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.get("id"));
        out.put("verifyCode", r.get("verify_code"));
        out.put("orderNo", r.get("order_no"));
        out.put("type", r.get("type"));
        out.put("operator", r.get("operator"));
        out.put("device", r.get("device"));
        out.put("result", r.get("result"));
        out.put("storeSubjectId", r.get("store_subject_id"));
        out.put("store", r.get("store_name") == null ? "—" : r.get("store_name"));
        out.put("time", toTimeText(r.get("create_time")));
        out.put("createTime", toTimeText(r.get("create_time")));
        return Result.ok(out);
    }

    // ---------- 分账快照详情 ----------

    @GetMapping("/snapshot/{id}")
    public Result<Map<String, Object>> snapshotDetail(@PathVariable Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select * from split_snapshot where id = ? and deleted = 0", id);
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "分账快照不存在");
        }
        Map<String, Object> r = rows.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.get("id"));
        out.put("snapshotNo", r.get("snapshot_no"));
        out.put("orderNo", r.get("order_no"));
        out.put("itemCount", r.get("item_count"));
        out.put("summary", summarizeOrderItems(r.get("order_id")));
        out.put("platformAmount", r.get("platform_amount"));
        out.put("storeAmount", r.get("store_amount"));
        out.put("channelAmount", r.get("channel_amount"));
        out.put("investorAmount", r.get("investor_amount"));
        out.put("supplierAmount", r.get("supplier_amount"));
        out.put("platformCommission", r.get("platform_commission"));
        out.put("platformBonus", r.get("platform_bonus"));
        out.put("totalCheck", r.get("total_check"));
        out.put("status", r.get("status"));
        out.put("createTime", toTimeText(r.get("create_time")));
        return Result.ok(out);
    }

    // ---------- 角色申请详情 ----------

    @GetMapping("/role-application/{id}")
    public Result<Map<String, Object>> roleApplicationDetail(@PathVariable Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select ra.*, u.nick_name as nick_name "
                        + "  from role_application ra "
                        + "  left join app_user u on u.id = ra.user_id and u.deleted = 0 "
                        + " where ra.id = ? and ra.deleted = 0", id);
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "角色申请不存在");
        }
        Map<String, Object> r = rows.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.get("id"));
        out.put("userId", r.get("user_id"));
        out.put("nickName", r.get("nick_name"));
        out.put("name", r.get("applicant_name"));
        out.put("phone", r.get("applicant_phone"));
        out.put("roleType", r.get("role_type"));
        out.put("status", r.get("status"));
        out.put("applyTime", toTimeText(r.get("apply_time")));
        out.put("reviewer", r.get("reviewer"));
        out.put("subjectId", r.get("subject_id"));
        // extra_form 为 JSON 字符串，按前端既有习惯平铺为 storeName / investLocation 等键
        out.put("extraForm", r.get("extra_form"));
        return Result.ok(out);
    }

    // ---------- 内部工具 ----------

    /** 聚合订单商品明细为一行摘要，如「五窨茉莉抹茶 x2、经典珍珠奶茶 x1」 */
    private String summarizeOrderItems(Object orderId) {
        if (orderId == null) {
            return "";
        }
        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "select product_name, quantity from order_item where order_id = ? and deleted = 0 "
                        + "order by id", ((Number) orderId).longValue());
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> it : items) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(it.get("product_name")).append(" x").append(it.get("quantity"));
        }
        return sb.toString();
    }

    private String toTimeText(Object value) {
        if (value instanceof java.time.LocalDateTime ldt) {
            return ldt.format(FMT);
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().format(FMT);
        }
        return value == null ? null : String.valueOf(value);
    }
}
