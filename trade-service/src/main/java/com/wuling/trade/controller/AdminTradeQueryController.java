package com.wuling.trade.controller;

import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 后台交易查询：支付单 / 退款单 / 核销记录 */
@RestController
@RequestMapping("/api/v1/admin/trade")
public class AdminTradeQueryController {

    private final JdbcTemplate jdbcTemplate;

    public AdminTradeQueryController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/payments")
    public Result<PageResult<Map<String, Object>>> payments(@RequestParam(defaultValue = "1") long current,
                                                            @RequestParam(defaultValue = "10") long size,
                                                            @RequestParam(required = false) String orderNo) {
        return Result.ok(pageOf("payment", like(orderNo, "order_no"), args(orderNo), "id desc", current, size));
    }

    @GetMapping("/refunds")
    public Result<PageResult<Map<String, Object>>> refunds(@RequestParam(defaultValue = "1") long current,
                                                           @RequestParam(defaultValue = "10") long size,
                                                           @RequestParam(required = false) String orderNo) {
        return Result.ok(pageOf("refund", like(orderNo, "order_no"), args(orderNo), "id desc", current, size));
    }

    @GetMapping("/verify-records/page")
    public Result<PageResult<Map<String, Object>>> verifyRecords(@RequestParam(defaultValue = "1") long current,
                                                                 @RequestParam(defaultValue = "10") long size,
                                                                 @RequestParam(required = false) String orderNo,
                                                                 @RequestParam(required = false) String type) {
        StringBuilder where = new StringBuilder(" where deleted = 0");
        List<Object> list = new ArrayList<>();
        if (orderNo != null && !orderNo.isBlank()) {
            where.append(" and order_no like ?");
            list.add("%" + orderNo + "%");
        }
        if (type != null && !type.isBlank()) {
            where.append(" and type = ?");
            list.add(type);
        }
        return Result.ok(pageOf("verify_record", where.toString(), list, "id desc", current, size));
    }

    /** 核销池：已支付待核销订单（含商品摘要） */
    @GetMapping("/verify-pool/page")
    public Result<PageResult<Map<String, Object>>> verifyPool(@RequestParam(defaultValue = "1") long current,
                                                              @RequestParam(defaultValue = "10") long size,
                                                              @RequestParam(required = false) Long storeSubjectId) {
        StringBuilder where = new StringBuilder(" where o.deleted = 0 and o.status = 'PAID'");
        List<Object> list = new ArrayList<>();
        if (storeSubjectId != null) {
            where.append(" and o.store_subject_id = ?");
            list.add(storeSubjectId);
        }
        String whereClause = where.toString();

        Long total = jdbcTemplate.queryForObject(
                "select count(*) from orders o" + whereClause, Long.class, list.toArray());

        List<Object> pageArgs = new ArrayList<>(list);
        pageArgs.add(size);
        pageArgs.add(Math.max(0, (current - 1) * size));

        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                "select o.id, o.order_no, o.store_subject_id, o.pickup_code, o.paid_amount, o.status, o.create_time,"
                        + " (select group_concat(concat(product_name, ' x', quantity) separator ', ')"
                        + "  from order_item where order_id = o.id and deleted = 0) as summary"
                        + " from orders o" + whereClause
                        + " order by o.id desc limit ? offset ?", pageArgs.toArray());

        return Result.ok(PageResult.of(records.stream().map(this::camelize).toList(), current, size,
                total == null ? 0L : total));
    }
    // ---------- 内部工具 ----------

    private String like(String value, String column) {
        if (value == null || value.isBlank()) {
            return " where deleted = 0";
        }
        return " where deleted = 0 and " + column + " like ?";
    }

    private List<Object> args(String value) {
        List<Object> list = new ArrayList<>();
        if (value != null && !value.isBlank()) {
            list.add("%" + value + "%");
        }
        return list;
    }

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

