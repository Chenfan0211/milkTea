package com.wuling.finance.controller;

import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.finance.service.LedgerService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台财务查询（只读）。
 * 覆盖：资金池、资金流水、分账快照、对账异常、主体账户。
 */
@RestController
@RequestMapping("/api/v1/admin/finance")
public class AdminFinanceQueryController {

    private final JdbcTemplate jdbcTemplate;
    private final LedgerService ledgerService;

    public AdminFinanceQueryController(JdbcTemplate jdbcTemplate, LedgerService ledgerService) {
        this.jdbcTemplate = jdbcTemplate;
        this.ledgerService = ledgerService;
    }

    // ---------- 资金池 ----------

    @GetMapping("/pool")
    public Result<List<Map<String, Object>>> pool() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select * from fund_pool where deleted = 0 order by id");
        if (rows.isEmpty()) {
            // 无记录时给出汇总视图，避免前端空态
            long total = nz(jdbcTemplate.queryForObject(
                    "select coalesce(sum(available_balance),0) from subject_account where deleted = 0", Long.class));
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("poolName", "平台资金池");
            fallback.put("totalBalance", total);
            return Result.ok(List.of(fallback));
        }
        return Result.ok(rows.stream().map(this::camelize).toList());
    }

    // ---------- 资金流水 ----------

    @GetMapping("/flows")
    public Result<PageResult<Map<String, Object>>> flows(@RequestParam(defaultValue = "1") long current,
                                                         @RequestParam(defaultValue = "10") long size,
                                                         @RequestParam(required = false) Long subjectId,
                                                         @RequestParam(required = false) String type,
                                                         @RequestParam(required = false) String orderNo,
                                                         @RequestParam(required = false) Boolean settled) {
        long pageCurrent = Math.max(1L, current);
        long pageSize = Math.min(200L, Math.max(1L, size));
        String normalizedType = normalizeFlowType(type);

        String from = " from fund_flow f"
                + " left join biz_subject s on s.id = f.subject_id and s.deleted = 0"
                + " left join subject_account a on a.id = f.account_id"
                + " left join orders o on o.id = f.order_id";
        StringBuilder where = new StringBuilder(" where f.deleted = 0");
        List<Object> args = new ArrayList<>();
        if (subjectId != null) {
            where.append(" and f.subject_id = ?");
            args.add(subjectId);
        }
        if (normalizedType != null) {
            if ("INCOME".equals(normalizedType)) {
                where.append(" and f.type in ('INCOME', 'SETTLE')");
            } else {
                where.append(" and f.type = ?");
                args.add(normalizedType);
            }
        }
        if (orderNo != null && !orderNo.isBlank()) {
            where.append(" and (f.order_no like ? or f.biz_no like ? or o.order_no like ?)");
            String keyword = "%" + orderNo.trim() + "%";
            args.add(keyword);
            args.add(keyword);
            args.add(keyword);
        }
        if (Boolean.TRUE.equals(settled)) {
            where.append(" and f.settlement_status = 'SETTLED'");
        } else if (Boolean.FALSE.equals(settled)) {
            where.append(" and f.settlement_status in ('PENDING', 'SETTLEABLE', 'FROZEN', 'CANCELED')");
        }

        Long total = jdbcTemplate.queryForObject(
                "select count(*)" + from + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add(Math.max(0L, (pageCurrent - 1L) * pageSize));
        String select = "select f.id,"
                + " f.flow_no,"
                + " case when f.type = 'SETTLE' then 'INCOME' else f.type end as type,"
                + " case when f.type = 'SETTLE' then '入账'"
                + "      when f.type = 'INCOME' then '入账'"
                + "      when f.type = 'WITHDRAW' then '提现'"
                + "      when f.type = 'REFUND' then '退款'"
                + "      when f.type = 'FREEZE' then '冻结'"
                + "      when f.type = 'UNFREEZE' then '解冻'"
                + "      else f.type end as type_name,"
                + " f.direction,"
                + " case when f.direction = 'in' then '入' when f.direction = 'out' then '出' else f.direction end as direction_name,"
                + " f.amount,"
                + " f.subject_id,"
                + " s.name as subject_name,"
                + " coalesce(f.role_type, a.role_type) as role_type,"
                + " f.account_id,"
                + " case when s.name is null then null"
                + "      else concat(s.name, '-', case coalesce(f.role_type, a.role_type)"
                + "          when 'PLATFORM' then '平台账户'"
                + "          when 'STORE' then '门店账户'"
                + "          when 'CHANNEL' then '资源方账户'"
                + "          when 'INVESTOR' then '投资人账户'"
                + "          when 'SUPPLIER' then '供应商账户'"
                + "          else '未知账户' end) end as account_name,"
                + " f.order_id,"
                + " coalesce(f.order_no, o.order_no) as order_no,"
                + " f.settlement_record_id,"
                + " f.settlement_status,"
                + " case f.settlement_status"
                + "      when 'PENDING' then '待结算'"
                + "      when 'SETTLEABLE' then '可结算'"
                + "      when 'FROZEN' then '冻结中'"
                + "      when 'SETTLED' then '已结算'"
                + "      when 'CANCELED' then '已取消'"
                + "      else null end as settlement_status_name,"
                + " f.biz_type,"
                + " f.biz_no,"
                + " f.balance_bucket,"
                + " case f.balance_bucket when 'AVAILABLE' then '可用余额'"
                + "      when 'FROZEN' then '冻结余额' else null end as balance_bucket_name,"
                + " f.balance_before,"
                + " f.change_amount,"
                + " f.balance_after,"
                + " f.remark,"
                + " f.create_time";
        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                select + from + where + " order by f.id desc limit ? offset ?", pageArgs.toArray());
        return Result.ok(PageResult.of(
                records.stream().map(this::camelize).toList(),
                pageCurrent,
                pageSize,
                total == null ? 0L : total));
    }

    private String normalizeFlowType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        String normalized = type.trim().toUpperCase();
        if (!List.of("INCOME", "WITHDRAW", "REFUND", "FREEZE", "UNFREEZE").contains(normalized)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的流水类型: " + type);
        }
        return normalized;
    }

    // ---------- 分账快照 ----------

    /**
     * 分账快照列表。
     *
     * <p>返回体额外聚合 order_item 得到 summary（商品信息），
     * 因为 split_snapshot 表本身不存商品明细，而列表页需要展示该列。
     * 若改走通用 CRUD 单表查询，该列恒为空。
     */
    @GetMapping("/snapshots")
    public Result<PageResult<Map<String, Object>>> snapshots(@RequestParam(defaultValue = "1") long current,
                                                             @RequestParam(defaultValue = "10") long size,
                                                             @RequestParam(required = false) String orderNo) {
        StringBuilder where = new StringBuilder(" where deleted = 0");
        List<Object> args = new ArrayList<>();
        if (orderNo != null && !orderNo.isBlank()) {
            where.append(" and order_no like ?");
            args.add("%" + orderNo + "%");
        }
        PageResult<Map<String, Object>> page = pageOf("split_snapshot", where.toString(), args, "id desc", current, size);
        for (Map<String, Object> row : page.getRecords()) {
            row.put("summary", summarizeOrderItems(row.get("orderId")));
        }
        return Result.ok(page);
    }

    /** 聚合订单商品明细为一行摘要，如「金桂轻乳茶 x1」 */
    private String summarizeOrderItems(Object orderId) {
        if (orderId == null) {
            return "";
        }
        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "select product_name, quantity from order_item where order_id = ? and deleted = 0 order by id",
                ((Number) orderId).longValue());
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

    // ---------- 对账异常 ----------

    @GetMapping("/reconcile")
    public Result<PageResult<Map<String, Object>>> reconcile(@RequestParam(defaultValue = "1") long current,
                                                             @RequestParam(defaultValue = "10") long size,
                                                             @RequestParam(required = false) String status) {
        StringBuilder where = new StringBuilder(" where deleted = 0");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" and status = ?");
            args.add(status);
        }
        return Result.ok(pageOf("reconcile_issue", where.toString(), args, "id desc", current, size));
    }

    // ---------- 主体账户 ----------

    @GetMapping("/accounts/page")
    public Result<PageResult<Map<String, Object>>> accountsPage(@RequestParam(defaultValue = "1") long current,
                                                                @RequestParam(defaultValue = "10") long size,
                                                                @RequestParam(required = false) String subjectId) {
        StringBuilder where = new StringBuilder(" where deleted = 0");
        List<Object> args = new ArrayList<>();
        if (subjectId != null && !subjectId.isBlank()) {
            where.append(" and subject_id = ?");
            args.add(Long.parseLong(subjectId));
        }
        return Result.ok(pageOf("subject_account", where.toString(), args, "id asc", current, size));
    }

    /** 冻结 / 解冻主体账户 */
    @PostMapping("/accounts/{subjectId}/freeze")
    public Result<Void> freeze(@PathVariable Long subjectId, @RequestParam Long amount) {
        try {
            ledgerService.manualFreeze(subjectId, amount);
        } catch (LedgerService.InsufficientBalanceException e) {
            return Result.fail(400, "余额不足或账户不存在");
        }
        return Result.ok();
    }

    @PostMapping("/accounts/{subjectId}/unfreeze")
    public Result<Void> unfreeze(@PathVariable Long subjectId, @RequestParam Long amount) {
        try {
            ledgerService.manualUnfreeze(subjectId, amount);
        } catch (LedgerService.InsufficientBalanceException e) {
            return Result.fail(400, "冻结金额不足或账户不存在");
        }
        return Result.ok();
    }

    // ---------- 内部工具 ----------

    private PageResult<Map<String, Object>> pageOf(String table, String where, List<Object> args,
                                                  String orderBy, long current, long size) {
        Long total = jdbcTemplate.queryForObject("select count(*) from " + table + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(Math.max(0, (current - 1) * size));
        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                "select * from " + table + where + " order by " + orderBy + " limit ? offset ?", pageArgs.toArray());
        return PageResult.of(records.stream().map(this::camelize).toList(), current, size,
                total == null ? 0L : total);
    }

    private long nz(Long value) {
        return value == null ? 0L : value;
    }

    private Map<String, Object> camelize(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((k, v) -> {
            StringBuilder sb = new StringBuilder();
            boolean upper = false;
            for (char c : k.toCharArray()) {
                if (c == '_') {
                    upper = true;
                } else {
                    sb.append(upper ? Character.toUpperCase(c) : c);
                    upper = false;
                }
            }
            result.put(sb.toString(), v);
        });
        return result;
    }
}

