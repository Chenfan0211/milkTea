package com.wuling.trade.controller;

import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import com.wuling.trade.entity.Payment;
import com.wuling.trade.service.PaymentRetryService;
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
    /** 支付异常重试（查三方并回写，不直接改状态） */
    private final PaymentRetryService paymentRetryService;

    public AdminTradeQueryController(JdbcTemplate jdbcTemplate,
                                     PaymentRetryService paymentRetryService) {
        this.jdbcTemplate = jdbcTemplate;
        this.paymentRetryService = paymentRetryService;
    }

    /**
     * 支付记录（后台列表）。
     *
     * <p><b>字段口径</b>：
     * <ul>
     *   <li>{@code orderNo} —— 系统订单号（储值充值单为 CZ 储值单号）；</li>
     *   <li>{@code transactionId} —— 三方流水订单号（储值支付时等于系统订单号）；</li>
     *   <li>{@code channel} —— 支付渠道原始码，中文由前端映射；</li>
     *   <li>{@code thirdStatus} / {@code standardStatus} —— 三方状态 / 系统标准状态。</li>
     * </ul>
     *
     * <p><b>搜索</b>：{@code orderNo} 与 {@code tradeNo} 都可单独或同时使用，
     * 两者为「与」关系；{@code orderNo} 额外匹配 {@code biz_no}，
     * 使「系统订单号 / 储值单号」一次输入即可命中。
     *
     * <p><b>为什么不再 select ***：显式列避免后续加列时把
     * {@code payer_openid}、{@code prepay_id} 等敏感/冗余字段带到前端。
     */
    @GetMapping("/payments")
    public Result<PageResult<Map<String, Object>>> payments(@RequestParam(defaultValue = "1") long current,
                                                            @RequestParam(defaultValue = "10") long size,
                                                            @RequestParam(required = false) String orderNo,
                                                            @RequestParam(required = false) String tradeNo,
                                                            @RequestParam(required = false) String standardStatus) {
        StringBuilder where = new StringBuilder(" where deleted = 0");
        List<Object> args = new ArrayList<>();
        if (orderNo != null && !orderNo.isBlank()) {
            // 订单号：同时匹配 order_no 与 biz_no（储值单号），避免两列各查一次
            where.append(" and (order_no like ? or biz_no like ?)");
            args.add("%" + orderNo + "%");
            args.add("%" + orderNo + "%");
        }
        if (tradeNo != null && !tradeNo.isBlank()) {
            // 流水订单号：三方订单号
            where.append(" and transaction_id like ?");
            args.add("%" + tradeNo + "%");
        }
        if (standardStatus != null && !standardStatus.isBlank()) {
            where.append(" and standard_status = ?");
            args.add(standardStatus);
        }
        // 单行字面量：字段一致性检查靠正则从 SQL 里提取列名，
        // 多段拼接的字符串会提取不到（见 DedicatedEndpointConsistencyTest）。
        return Result.ok(pageOf("payment",
                "select id, payment_no, order_id, order_no, biz_type, biz_no, amount, channel,"
                        + " third_status, standard_status, transaction_id, callback_time, create_time from ",
                where.toString(), args, "id desc", current, size));
    }

    /**
     * 支付异常重试：主动查询三方订单状态并回写。
     *
     * <p>只对 {@code standard_status = FAILED} 的支付单开放；
     * 真正的状态以三方返回为准，<b>不会把状态直接改成成功</b>。
     *
     * @param id 支付单主键
     * @return 回写后的支付单（含最新 thirdStatus / standardStatus）
     */
    @PostMapping("/payments/{id}/retry")
    public Result<Map<String, Object>> retryPayment(@PathVariable Long id) {
        Payment payment = paymentRetryService.retry(id);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", payment.getId());
        view.put("orderNo", payment.getOrderNo());
        view.put("transactionId", payment.getTransactionId());
        view.put("channel", payment.getChannel());
        view.put("thirdStatus", payment.getThirdStatus());
        view.put("standardStatus", payment.getStandardStatus());
        view.put("callbackTime", payment.getCallbackTime());
        return Result.ok(view);
    }

    @GetMapping("/refunds")
    public Result<PageResult<Map<String, Object>>> refunds(@RequestParam(defaultValue = "1") long current,
                                                           @RequestParam(defaultValue = "10") long size,
                                                           @RequestParam(required = false) String orderNo,
                                                           @RequestParam(required = false) String status) {
        // 关联订单取门店 + order_item 聚合商品摘要（单行字面量，满足字段一致性检查）
        StringBuilder where = new StringBuilder(" where r.deleted = 0");
        List<Object> list = new ArrayList<>();
        if (orderNo != null && !orderNo.isBlank()) {
            where.append(" and r.order_no like ?");
            list.add("%" + orderNo + "%");
        }
        if (status != null && !status.isBlank()) {
            where.append(" and r.status = ?");
            list.add(status);
        }
        String whereClause = where.toString();

        Long total = jdbcTemplate.queryForObject(
                "select count(*) from refund r" + whereClause, Long.class, list.toArray());

        List<Object> pageArgs = new ArrayList<>(list);
        pageArgs.add(size);
        pageArgs.add(Math.max(0, (current - 1) * size));

        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                "select r.id, r.refund_no, r.order_no, r.amount, r.status, r.reason, r.third_refund_no, r.apply_time, r.complete_time, o.store_subject_id, s.name as store,"
                        + " (select group_concat(concat(oi.product_name, ' x', oi.quantity) separator ', ')"
                        + "  from order_item oi where oi.order_id = o.id and oi.deleted = 0) as summary"
                        + " from refund r"
                        + " left join orders o on o.id = r.order_id"
                        + " left join biz_subject s on s.id = o.store_subject_id"
                        + whereClause
                        + " order by r.id desc limit ? offset ?", pageArgs.toArray());

        return Result.ok(PageResult.of(records.stream().map(this::camelize).toList(),
                current, size, total == null ? 0L : total));
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

    /**
     * 核销池：已支付待核销订单 + 待核销积分兑换单（统一分页）。
     *
     * <p><b>为什么要合池</b>：门店「执行核销」既要点单也要兑品，
     * 拆成两张表前端就得翻两个页面。这里用 UNION ALL 把两类单据统一成一行：
     * <ul>
     *   <li>type = order：点单订单（orders，status=PAID），按取餐码核销；</li>
     *   <li>type = exchange：积分兑换单（exchange_order，status=PENDING），按自提码核销。</li>
     * </ul>
     *
     * <p><b>字段口径</b>：
     * <ul>
     *   <li>pickupCode —— 点单 = orders.pickup_code；兑换 = exchange_order.pickup_code；</li>
     *   <li>product    —— 点单 = order_item 聚合；兑换 = points_product.name；</li>
     *   <li>spec       —— 点单 = order_item.spec_snapshot 聚合；兑换为空；</li>
     *   <li>amount     —— 点单 = paid_amount（分）；兑换 = 0（金额用 points 表示，前端区分）；</li>
     *   <li>points     —— 仅兑换单有值，前端金额列显示「N 时光币」。</li>
     * </ul>
     *
     * <p><b>历史数据修复</b>：PAID 订单若缺取餐码（历史种子数据未生成），
     * 这里用 order_no 后 4 位兜底回显，避免核销时因取餐码为空而无法操作；
     * 数据修复见 V45 迁移。
     *
     * @param search         取餐码 / 订单号 / 兑换单号 模糊匹配
     * @param type           order / exchange / 空 = 全部
     * @param storeSubjectId 门店过滤（仅对点单生效；兑换无门店归属）
     */
    @GetMapping("/verify-pool/page")
    public Result<PageResult<Map<String, Object>>> verifyPool(@RequestParam(defaultValue = "1") long current,
                                                              @RequestParam(defaultValue = "10") long size,
                                                              @RequestParam(required = false) String search,
                                                              @RequestParam(required = false) String type,
                                                              @RequestParam(required = false) Long storeSubjectId) {
        boolean onlyOrder = type != null && "order".equalsIgnoreCase(type);
        boolean onlyExchange = type != null && "exchange".equalsIgnoreCase(type);
        boolean includeOrder = !onlyExchange;
        boolean includeExchange = !onlyOrder;

        StringBuilder orderWhere = new StringBuilder(" where o.deleted = 0 and o.status = 'PAID'");
        List<Object> orderArgs = new ArrayList<>();
        if (storeSubjectId != null) {
            orderWhere.append(" and o.store_subject_id = ?");
            orderArgs.add(storeSubjectId);
        }
        if (search != null && !search.isBlank()) {
            // 取餐码 / 订单号一起模糊匹配：门店人员可能只记得其中一个
            orderWhere.append(" and (o.pickup_code like ? or o.order_no like ?)");
            orderArgs.add("%" + search + "%");
            orderArgs.add("%" + search + "%");
        }

        StringBuilder exchangeWhere = new StringBuilder(" where e.deleted = 0 and e.status = 'PENDING'");
        List<Object> exchangeArgs = new ArrayList<>();
        if (search != null && !search.isBlank()) {
            exchangeWhere.append(" and (e.pickup_code like ? or e.exchange_no like ?)");
            exchangeArgs.add("%" + search + "%");
            exchangeArgs.add("%" + search + "%");
        }

        long total = 0L;
        if (includeOrder) {
            Long orderTotal = jdbcTemplate.queryForObject(
                    "select count(*) from orders o" + orderWhere, Long.class, orderArgs.toArray());
            total += orderTotal == null ? 0L : orderTotal;
        }
        if (includeExchange) {
            Long exchangeTotal = jdbcTemplate.queryForObject(
                    "select count(*) from exchange_order e" + exchangeWhere,
                    Long.class, exchangeArgs.toArray());
            total += exchangeTotal == null ? 0L : exchangeTotal;
        }

        List<Object> pageArgs = new ArrayList<>();
        if (includeOrder) {
            pageArgs.addAll(orderArgs);
        }
        if (includeExchange) {
            pageArgs.addAll(exchangeArgs);
        }
        pageArgs.add(size);
        pageArgs.add(Math.max(0, (current - 1) * size));

        StringBuilder unionSql = new StringBuilder();
        if (includeOrder) {
            unionSql.append("select o.id as source_id, 'order' as row_type, o.order_no as order_no,")
                    .append(" o.store_subject_id as store_subject_id, o.pickup_code as pickup_code,")
                    .append(" o.paid_amount as paid_amount, null as points, o.create_time as create_time,")
                    .append(" (select group_concat(concat(product_name, ' x', quantity) separator ', ')")
                    .append("  from order_item where order_id = o.id and deleted = 0) as summary,")
                    .append(" (select group_concat(spec_snapshot separator ', ')")
                    .append("  from order_item where order_id = o.id and deleted = 0) as specs")
                    .append(" from orders o").append(orderWhere);
        }
        if (includeOrder && includeExchange) {
            unionSql.append(" union all ");
        }
        if (includeExchange) {
            unionSql.append("select e.id as source_id, 'exchange' as row_type, e.exchange_no as order_no,")
                    .append(" null as store_subject_id, e.pickup_code as pickup_code,")
                    .append(" 0 as paid_amount, e.points as points, e.create_time as create_time,")
                    .append(" p.name as summary, null as specs")
                    .append(" from exchange_order e")
                    .append(" left join points_product p on p.id = e.points_product_id")
                    .append(exchangeWhere);
        }

        String pageSql = "select * from (" + unionSql + ") pool"
                + " order by create_time desc, (row_type = 'order') desc, source_id desc"
                + " limit ? offset ?";
        List<Map<String, Object>> records = jdbcTemplate.queryForList(pageSql, pageArgs.toArray()).stream()
                .map(this::verifyPoolRow)
                .toList();
        return Result.ok(PageResult.of(records, current, size, total));
    }

    /** 将订单/兑换 UNION 行转换为前端沿用字段，保持原接口契约。 */
    private Map<String, Object> verifyPoolRow(Map<String, Object> source) {
        String rowType = String.valueOf(source.get("row_type"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", ("exchange".equals(rowType) ? "e-" : "o-") + source.get("source_id"));
        row.put("type", rowType);
        row.put("orderNo", source.get("order_no"));
        String pickup = (String) source.get("pickup_code");
        row.put("pickupCode", "order".equals(rowType) && (pickup == null || pickup.isBlank())
                ? tail4((String) source.get("order_no")) : pickup);
        row.put("pickupCodeFromDb", pickup);
        row.put("product", source.get("summary"));
        row.put("spec", source.get("specs"));
        row.put("amount", "exchange".equals(rowType) ? 0L : source.get("paid_amount"));
        row.put("points", "exchange".equals(rowType) ? source.get("points") : null);
        row.put("storeSubjectId", "exchange".equals(rowType) ? null : source.get("store_subject_id"));
        row.put("createTime", source.get("create_time"));
        return row;
    }
    /** 取单号后 4 位作为兜底取餐码（历史数据修复回显用） */
    private String tail4(String orderNo) {
        if (orderNo == null || orderNo.length() <= 4) {
            return orderNo;
        }
        return orderNo.substring(orderNo.length() - 4);
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

    /** 查询全列（refund / verify_record 等只读表，字段少且无敏感列）。 */
    private PageResult<Map<String, Object>> pageOf(String table, String where, List<Object> args,
                                                  String orderBy, long current, long size) {
        return pageOf(table, "select * from ", where, args, orderBy, current, size);
    }

    /**
     * 分页查询（可指定返回列）。
     *
     * @param columns 返回列（SQL 片段，调用方传入字面量，不接受外部输入）
     */
    private PageResult<Map<String, Object>> pageOf(String table, String selectPrefix, String where, List<Object> args,
                                                  String orderBy, long current, long size) {
        Long total = jdbcTemplate.queryForObject("select count(*) from " + table + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(Math.max(0, (current - 1) * size));
        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                selectPrefix + table + where + " order by " + orderBy + " limit ? offset ?",
                pageArgs.toArray());
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

