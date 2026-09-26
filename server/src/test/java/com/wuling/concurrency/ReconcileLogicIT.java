package com.wuling.concurrency;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资金对账逻辑验证（第 8 期）。
 *
 * <p>验证对账查询能真正发现三类不一致。这里直接执行与
 * {@code ReconcileService} 相同的 SQL，构造数据后断言能被查出 ——
 * 确保对账不是"永远返回 0"的空转逻辑。
 *
 * <p>依赖本地 MySQL 隧道（127.0.0.1:13306）。
 * 通过 {@code -Ddb.it=true} 开启；未开启自动跳过。
 */
class ReconcileLogicIT {

    private static final String URL =
            "jdbc:mysql://127.0.0.1:13306/wuling?useUnicode=true&characterEncoding=utf8"
                    + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
    /**
      口令从环境变量读取，绝不硬编码 —— 测试文件同样会进仓库。
      运行：-Ddb.it=true 且设置 MYSQL_USER / MYSQL_PASSWORD
    */
    private static final String USER = System.getenv().getOrDefault("MYSQL_USER", "wuling");
    private static final String PWD = System.getenv().getOrDefault("MYSQL_PASSWORD", "");

    private boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("db.it", "false"));
    }

    private Connection conn() throws Exception {
        return DriverManager.getConnection(URL, USER, PWD);
    }

    // ---------- 检查项 1：已核销但无分账快照 ----------

    @Test
    void shouldDetectCompletedOrderWithoutSplitSnapshot() throws Exception {
        if (!enabled()) { return; }

        long orderId = 999_100_001L;
        String orderNo = "IT-RECON-NOSPLIT";
        cleanup(orderId, orderNo);

        try (Connection c = conn()) {
            insertOrder(c, orderId, orderNo, "COMPLETED", 1000L, "PAID");

            List<String> found = queryMissingSplit(c);
            assertTrue(found.contains(orderNo),
                    "已核销但无分账快照的订单应被对账发现");
        } finally {
            cleanup(orderId, orderNo);
        }
    }

    @Test
    void shouldNotReportOrderThatHasSnapshot() throws Exception {
        if (!enabled()) { return; }

        long orderId = 999_100_002L;
        String orderNo = "IT-RECON-HASSNAPSHOT";
        cleanup(orderId, orderNo);

        try (Connection c = conn()) {
            insertOrder(c, orderId, orderNo, "COMPLETED", 1000L, "PAID");
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into split_snapshot (snapshot_no, order_id, order_no, item_count, "
                            + "platform_amount, store_amount, channel_amount, investor_amount, supplier_amount, deleted) "
                            + "values (?, ?, ?, 1, 100, 400, 0, 100, 400, 0)")) {
                ps.setString(1, "SN-" + orderNo);
                ps.setLong(2, orderId);
                ps.setString(3, orderNo);
                ps.executeUpdate();
            }

            List<String> found = queryMissingSplit(c);
            assertTrue(!found.contains(orderNo),
                    "已有快照的订单不应被误报为漏分账");
        } finally {
            cleanup(orderId, orderNo);
        }
    }

    @Test
    void shouldIgnorePaidOrder() throws Exception {
        if (!enabled()) { return; }

        long orderId = 999_100_003L;
        String orderNo = "IT-RECON-PAIDONLY";
        cleanup(orderId, orderNo);

        try (Connection c = conn()) {
            // 仅支付未完成：不应分账，也不应报异常
            insertOrder(c, orderId, orderNo, "PAID", 1000L, "PAID");

            List<String> found = queryMissingSplit(c);
            assertTrue(!found.contains(orderNo),
                    "未完成订单不应被报为漏分账（分账发生在完成时）");
        } finally {
            cleanup(orderId, orderNo);
        }
    }

    // ---------- 检查项 2：已退款但未冲正 ----------

    @Test
    void shouldDetectCanceledRefundedOrderWithPendingSettlement() throws Exception {
        if (!enabled()) { return; }

        long orderId = 999_100_004L;
        String orderNo = "IT-RECON-NOREVERSE";
        cleanup(orderId, orderNo);

        try (Connection c = conn()) {
            insertOrder(c, orderId, orderNo, "CANCELED", "REFUNDED", 1000L, "PAID");
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into settlement_record (record_no, subject_id, snapshot_id, order_id, "
                            + "amount, status, deleted) values (?, 101, 1, ?, 300, 'PENDING', 0)")) {
                ps.setString(1, "SR-" + orderNo);
                ps.setLong(2, orderId);
                ps.executeUpdate();
            }

            List<String> found = queryMissingReverse(c);
            assertTrue(found.contains(orderNo),
                    "已退款取消但仍挂 PENDING 台账的订单应被对账发现");
        } finally {
            cleanup(orderId, orderNo);
        }
    }

    @Test
    void shouldIgnoreCanceledRefundedOrderWithCanceledSettlement() throws Exception {
        if (!enabled()) { return; }

        long orderId = 999_100_005L;
        String orderNo = "IT-RECON-REVERSED";
        cleanup(orderId, orderNo);

        try (Connection c = conn()) {
            insertOrder(c, orderId, orderNo, "CANCELED", "REFUNDED", 1000L, "PAID");
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into settlement_record (record_no, subject_id, snapshot_id, order_id, "
                            + "amount, status, deleted) values (?, 101, 1, ?, 300, 'CANCELED', 0)")) {
                ps.setString(1, "SR-" + orderNo);
                ps.setLong(2, orderId);
                ps.executeUpdate();
            }

            List<String> found = queryMissingReverse(c);
            assertTrue(!found.contains(orderNo),
                    "已退款取消且已冲正（CANCELED）的订单不应被误报");
        } finally {
            cleanup(orderId, orderNo);
        }
    }

    @Test
    void shouldIgnoreCanceledOrderWithoutRefundedStatus() throws Exception {
        if (!enabled()) { return; }

        long orderId = 999_100_007L;
        String orderNo = "IT-RECON-CANCELED";
        cleanup(orderId, orderNo);

        try (Connection c = conn()) {
            insertOrder(c, orderId, orderNo, "CANCELED", null, 1000L, "PAID");
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into settlement_record (record_no, subject_id, snapshot_id, order_id, "
                            + "amount, status, deleted) values (?, 101, 1, ?, 300, 'PENDING', 0)")) {
                ps.setString(1, "SR-" + orderNo);
                ps.setLong(2, orderId);
                ps.executeUpdate();
            }

            List<String> found = queryMissingReverse(c);
            assertTrue(!found.contains(orderNo),
                    "CANCELED 但 refund_status 未达到 REFUNDED 的订单不应被报为退款未冲正");
        } finally {
            cleanup(orderId, orderNo);
        }
    }
    // ---------- 检查项 3：分账金额不一致 ----------

    @Test
    void shouldDetectSplitAmountMismatch() throws Exception {
        if (!enabled()) { return; }

        long orderId = 999_100_006L;
        String orderNo = "IT-RECON-MISMATCH";
        cleanup(orderId, orderNo);

        try (Connection c = conn()) {
            insertOrder(c, orderId, orderNo, "COMPLETED", 1000L, "PAID");
            // 五方之和 = 900 ≠ 实付 1000
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into split_snapshot (snapshot_no, order_id, order_no, item_count, "
                            + "platform_amount, store_amount, channel_amount, investor_amount, supplier_amount, deleted) "
                            + "values (?, ?, ?, 1, 100, 400, 0, 100, 300, 0)")) {
                ps.setString(1, "SN-" + orderNo);
                ps.setLong(2, orderId);
                ps.setString(3, orderNo);
                ps.executeUpdate();
            }

            List<String> found = querySplitMismatch(c);
            assertTrue(found.contains(orderNo),
                    "五方之和与实付不一致的订单应被对账发现");
        } finally {
            cleanup(orderId, orderNo);
        }
    }

    // ---------- SQL（与 ReconcileService 保持一致） ----------

    private List<String> queryMissingSplit(Connection c) throws Exception {
        String sql = "select o.order_no from orders o "
                + "left join split_snapshot s on s.order_id = o.id and s.deleted = 0 "
                + "where o.deleted = 0 and o.status = 'COMPLETED' and s.id is null "
                + "and o.order_no like 'IT-RECON%'";
        return collect(c, sql);
    }

    private List<String> queryMissingReverse(Connection c) throws Exception {
        String sql = "select distinct o.order_no from orders o "
                + "join settlement_record r on r.order_id = o.id and r.deleted = 0 "
                + "where o.deleted = 0 and o.status = 'CANCELED' and o.refund_status = 'REFUNDED' and r.status = 'PENDING' "
                + "and o.order_no like 'IT-RECON%'";
        return collect(c, sql);
    }

    private List<String> querySplitMismatch(Connection c) throws Exception {
        String sql = "select s.order_no from split_snapshot s join orders o on o.id = s.order_id "
                + "where s.deleted = 0 and o.deleted = 0 "
                + "and (s.platform_amount + s.store_amount + s.channel_amount + s.investor_amount + s.supplier_amount) <> o.paid_amount "
                + "and s.order_no like 'IT-RECON%'";
        return collect(c, sql);
    }

    private List<String> collect(Connection c, String sql) throws Exception {
        List<String> list = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            while (r.next()) {
                list.add(r.getString(1));
            }
        }
        return list;
    }

    // ---------- 测试数据构造与清理 ----------

    private void insertOrder(Connection c, long id, String orderNo, String status,
                             long paidAmount, String payStatus) throws Exception {
        insertOrder(c, id, orderNo, status, null, paidAmount, payStatus);
    }

    private void insertOrder(Connection c, long id, String orderNo, String status, String refundStatus,
                             long paidAmount, String payStatus) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "insert into orders (id, order_no, user_id, store_subject_id, status, refund_status, pay_status, "
                        + "total_amount, paid_amount, deleted) values (?, ?, 1, 101, ?, ?, ?, ?, ?, 0)")) {
            ps.setLong(1, id);
            ps.setString(2, orderNo);
            ps.setString(3, status);
            ps.setString(4, refundStatus);
            ps.setString(5, payStatus);
            ps.setLong(6, paidAmount);
            ps.setLong(7, paidAmount);
            ps.executeUpdate();
        }
    }

    private void cleanup(long orderId, String orderNo) throws Exception {
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.executeUpdate("delete from settlement_record where order_id = " + orderId);
            s.executeUpdate("delete from split_snapshot where order_id = " + orderId);
            s.executeUpdate("delete from orders where id = " + orderId);
            s.executeUpdate("delete from reconcile_issue where order_no = '" + orderNo + "'");
        }
    }
}
