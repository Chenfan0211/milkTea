package com.wuling.concurrency;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 0 期并发安全加固 —— 验证测试。
 *
 * 覆盖 docs/微服务改造方案.md 第四章列出的竞态：
 *   1. 优惠券并发领取不超发
 *   2. 积分商品并发兑换不超兑
 *   3. 资金账户并发提现不超提
 *   4. 同一订单并发分账只产生一份快照
 *   5. 同一用户并发签到只记一次
 *   6. 储值并发充值不丢更新
 *   7. CHECK 约束兜底：库存/余额不能为负
 *
 * 依赖本地 MySQL 隧道（127.0.0.1:13306）。
 * 通过 -Ddb.it=true 开启；未开启自动跳过，保证 CI 不依赖外部环境。
 *
 * 本测试直接对数据库施加并发压力（不经过 Spring 容器），
 * 目的是验证「唯一索引 + 原子 SQL + CHECK 约束」这三层防线本身是否成立。
 */
class ConcurrencySafetyIT {

    private static final String URL =
            "jdbc:mysql://127.0.0.1:13306/wuling?useUnicode=true&characterEncoding=utf8"
                    + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
    private static final String USER = "wuling";
    private static final String PWD = "wuling123";

    private static final int THREADS = 50;

    private boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("db.it", "false"));
    }

    private Connection conn() throws Exception {
        return DriverManager.getConnection(URL, USER, PWD);
    }

    /** 并发执行任务，返回成功次数 */
    private int runConcurrently(Runnable task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger success = new AtomicInteger();
        List<Exception> errors = new ArrayList<>();

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    task.run();
                    success.incrementAndGet();
                } catch (Exception e) {
                    synchronized (errors) { errors.add(e); }
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发任务超时未完成");
        pool.shutdownNow();
        return success.get();
    }

    // ==================== 1. 优惠券并发领取 ====================

    @Test
    void couponStockMustNotBeOversold() throws Exception {
        if (!enabled()) { return; }

        long couponId;
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.executeUpdate("insert into coupon (code, name, type, amount, threshold, stock, status) "
                    + "values ('IT-COUPON-1', '并发测试券', 'CASH', 100, 0, 10, 'enabled')");
            try (ResultSet r = s.executeQuery("select last_insert_id()")) { r.next(); couponId = r.getLong(1); }
        }

        try {
            AtomicInteger claimed = new AtomicInteger();
            runConcurrently(() -> {
                try (Connection c = conn();
                     PreparedStatement ps = c.prepareStatement(
                             "update coupon set stock = stock - 1 where id = ? and stock > 0 and status = 'enabled'")) {
                    ps.setLong(1, couponId);
                    if (ps.executeUpdate() == 1) { claimed.incrementAndGet(); }
                } catch (Exception ignored) { }
            });

            int stock;
            try (Connection c = conn(); Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("select stock from coupon where id = " + couponId)) {
                r.next(); stock = r.getInt(1);
            }

            // 库存 10，50 线程抢：最多成功 10 次，库存精确为 0，绝不为负
            assertEquals(10, claimed.get(), "成功领取数应精确等于初始库存");
            assertEquals(0, stock, "库存应精确扣减为 0");
            assertTrue(stock >= 0, "库存不得为负（超发）");
        } finally {
            try (Connection c = conn(); Statement s = c.createStatement()) {
                s.executeUpdate("delete from coupon where id = " + couponId);
            }
        }
    }

    @Test
    void checkConstraintMustRejectNegativeStock() throws Exception {
        if (!enabled()) { return; }
        long couponId;
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.executeUpdate("insert into coupon (code, name, type, amount, threshold, stock, status) "
                    + "values ('IT-COUPON-CK', 'CHECK测试券', 'CASH', 100, 0, 1, 'enabled')");
            try (ResultSet r = s.executeQuery("select last_insert_id()")) { r.next(); couponId = r.getLong(1); }
        }
        try {
            boolean rejected = false;
            try (Connection c = conn(); Statement s = c.createStatement()) {
                s.executeUpdate("update coupon set stock = -1 where id = " + couponId);
            } catch (Exception e) {
                rejected = true;
            }
            assertTrue(rejected, "CHECK 约束必须拒绝负库存");
        } finally {
            try (Connection c = conn(); Statement s = c.createStatement()) {
                s.executeUpdate("delete from coupon where id = " + couponId);
            }
        }
    }

    // ==================== 2. 积分商品并发兑换 ====================

    @Test
    void pointsProductStockMustNotBeOversold() throws Exception {
        if (!enabled()) { return; }

        long productId;
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.executeUpdate("insert into points_product (code, name, points, stock, status) "
                    + "values ('IT-PP-1', '并发兑换品', 10, 5, 'enabled')");
            try (ResultSet r = s.executeQuery("select last_insert_id()")) { r.next(); productId = r.getLong(1); }
        }
        try {
            AtomicInteger ok = new AtomicInteger();
            runConcurrently(() -> {
                try (Connection c = conn();
                     PreparedStatement ps = c.prepareStatement(
                             "update points_product set stock = stock - 1 where id = ? and stock > 0 and status = 'enabled'")) {
                    ps.setLong(1, productId);
                    if (ps.executeUpdate() == 1) { ok.incrementAndGet(); }
                } catch (Exception ignored) { }
            });
            int stock;
            try (Connection c = conn(); Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("select stock from points_product where id = " + productId)) {
                r.next(); stock = r.getInt(1);
            }
            assertEquals(5, ok.get(), "成功兑换数应精确等于初始库存");
            assertEquals(0, stock, "库存应精确扣减为 0");
        } finally {
            try (Connection c = conn(); Statement s = c.createStatement()) {
                s.executeUpdate("delete from points_product where id = " + productId);
            }
        }
    }

    // ==================== 3. 资金账户并发提现 ====================

    @Test
    void withdrawalMustNotOverdraw() throws Exception {
        if (!enabled()) { return; }

        long subjectId = 999_000_001L;
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.executeUpdate("delete from subject_account where subject_id = " + subjectId);
            s.executeUpdate("insert into subject_account (subject_id, role_type, available_balance, "
                    + "frozen_balance, total_income, total_withdrawn, version) "
                    + "values (" + subjectId + ", 'STORE', 1000, 0, 0, 0, 0)");
        }
        try {
            // 100 线程各提 100 分，账户仅 1000 分：最多成功 10 次
            AtomicInteger ok = new AtomicInteger();
            runConcurrently(() -> {
                try (Connection c = conn();
                     PreparedStatement ps = c.prepareStatement(
                             "update subject_account set available_balance = available_balance - 100, "
                           + "frozen_balance = frozen_balance + 100, version = version + 1 "
                           + "where subject_id = ? and available_balance >= 100")) {
                    ps.setLong(1, subjectId);
                    if (ps.executeUpdate() == 1) { ok.incrementAndGet(); }
                } catch (Exception ignored) { }
            });

            long available, frozen;
            try (Connection c = conn(); Statement s = c.createStatement();
                 ResultSet r = s.executeQuery(
                         "select available_balance, frozen_balance from subject_account where subject_id = " + subjectId)) {
                r.next(); available = r.getLong(1); frozen = r.getLong(2);
            }
            assertEquals(10, ok.get(), "成功提现次数应精确等于余额可支撑的次数");
            assertEquals(0, available, "可用余额应精确扣减为 0");
            assertEquals(1000, frozen, "冻结金额应精确等于成功提现总额");
            assertTrue(available >= 0, "可用余额不得为负（超提）");
        } finally {
            try (Connection c = conn(); Statement s = c.createStatement()) {
                s.executeUpdate("delete from subject_account where subject_id = " + subjectId);
            }
        }
    }

    // ==================== 4. 同一订单并发分账 ====================

    @Test
    void splitSnapshotMustBeUniquePerOrder() throws Exception {
        if (!enabled()) { return; }

        long orderId = 999_000_002L;
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.executeUpdate("delete from split_snapshot where order_id = " + orderId);
        }
        try {
            AtomicInteger ok = new AtomicInteger();
            AtomicInteger conflict = new AtomicInteger();
            runConcurrently(() -> {
                try (Connection c = conn();
                     PreparedStatement ps = c.prepareStatement(
                             "insert into split_snapshot (snapshot_no, order_id, order_no, item_count, "
                           + "platform_amount, store_amount, channel_amount, investor_amount, supplier_amount) "
                           + "values (?, ?, 'IT-ORDER', 1, 10, 20, 0, 0, 30)")) {
                    ps.setString(1, "SN-IT-" + System.nanoTime());
                    ps.setLong(2, orderId);
                    ps.executeUpdate();
                    ok.incrementAndGet();
                } catch (Exception e) {
                    conflict.incrementAndGet();
                }
            });

            int count;
            try (Connection c = conn(); Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("select count(*) from split_snapshot where order_id = " + orderId)) {
                r.next(); count = r.getInt(1);
            }
            // 唯一索引保证：50 线程并发插入同一订单，最终只可能有 1 条
            assertEquals(1, count, "同一订单只能有一份分账快照（防重复分账）");
            assertEquals(1, ok.get(), "只有 1 次插入成功");
            assertEquals(THREADS - 1, conflict.get(), "其余全部被唯一索引拦截");
        } finally {
            try (Connection c = conn(); Statement s = c.createStatement()) {
                s.executeUpdate("delete from split_snapshot where order_id = " + orderId);
            }
        }
    }

    // ==================== 5. 同一用户并发签到 ====================

    @Test
    void concurrentSignInMustBeIdempotent() throws Exception {
        if (!enabled()) { return; }

        long userId = 999_000_003L;
        String today = java.time.LocalDate.now().toString();
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.executeUpdate("delete from points_signin where user_id = " + userId);
        }
        try {
            AtomicInteger ok = new AtomicInteger();
            runConcurrently(() -> {
                try (Connection c = conn();
                     PreparedStatement ps = c.prepareStatement(
                             "insert into points_signin (user_id, sign_date) values (?, ?)")) {
                    ps.setLong(1, userId);
                    ps.setString(2, today);
                    ps.executeUpdate();
                    ok.incrementAndGet();
                } catch (Exception ignored) { }
            });

            int count;
            try (Connection c = conn(); Statement s = c.createStatement();
                 ResultSet r = s.executeQuery(
                         "select count(*) from points_signin where user_id = " + userId + " and sign_date = '" + today + "'")) {
                r.next(); count = r.getInt(1);
            }
            assertEquals(1, count, "同一用户同一天只能签到一次（唯一索引保证）");
            assertEquals(1, ok.get(), "只有 1 次签到成功");
        } finally {
            try (Connection c = conn(); Statement s = c.createStatement()) {
                s.executeUpdate("delete from points_signin where user_id = " + userId);
            }
        }
    }

    // ==================== 6. 并发充值不丢更新 ====================

    @Test
    void concurrentRechargeMustNotLoseUpdates() throws Exception {
        if (!enabled()) { return; }

        long userId;
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.executeUpdate("insert into app_user (open_id, nick_name, points, balance, status) "
                    + "values ('IT-OPEN-RECHARGE', '并发充值', 0, 0, 1)");
            try (ResultSet r = s.executeQuery("select last_insert_id()")) { r.next(); userId = r.getLong(1); }
        }
        try {
            // 50 线程各充 100 分：原子 UPDATE 下总额应精确为 5000
            runConcurrently(() -> {
                try (Connection c = conn();
                     PreparedStatement ps = c.prepareStatement(
                             "update app_user set balance = balance + 100 where id = ?")) {
                    ps.setLong(1, userId);
                    ps.executeUpdate();
                } catch (Exception ignored) { }
            });

            long balance;
            try (Connection c = conn(); Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("select balance from app_user where id = " + userId)) {
                r.next(); balance = r.getLong(1);
            }
            assertEquals(5000, balance, "并发充值不得丢失更新，总额应为 50*100");
        } finally {
            try (Connection c = conn(); Statement s = c.createStatement()) {
                s.executeUpdate("delete from app_user where id = " + userId);
            }
        }
    }

    // ==================== 7. 并发扣积分不超扣 ====================

    @Test
    void concurrentPointsDeductionMustNotGoNegative() throws Exception {
        if (!enabled()) { return; }

        long userId;
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.executeUpdate("insert into app_user (open_id, nick_name, points, balance, status) "
                    + "values ('IT-OPEN-POINTS', '并发扣币', 500, 0, 1)");
            try (ResultSet r = s.executeQuery("select last_insert_id()")) { r.next(); userId = r.getLong(1); }
        }
        try {
            AtomicInteger ok = new AtomicInteger();
            // 50 线程各扣 100，仅 500 分：最多成功 5 次
            runConcurrently(() -> {
                try (Connection c = conn();
                     PreparedStatement ps = c.prepareStatement(
                             "update app_user set points = points - 100 where id = ? and points - 100 >= 0")) {
                    ps.setLong(1, userId);
                    if (ps.executeUpdate() == 1) { ok.incrementAndGet(); }
                } catch (Exception ignored) { }
            });

            long points;
            try (Connection c = conn(); Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("select points from app_user where id = " + userId)) {
                r.next(); points = r.getLong(1);
            }
            assertEquals(5, ok.get(), "成功扣减次数应精确等于余额可支撑的次数");
            assertEquals(0, points, "积分应精确扣减为 0");
            assertTrue(points >= 0, "积分不得为负（超扣）");
        } finally {
            try (Connection c = conn(); Statement s = c.createStatement()) {
                s.executeUpdate("delete from app_user where id = " + userId);
            }
        }
    }

    // ==================== 8. 优惠券并发重复领取被拦截 ====================

    @Test
    void concurrentDuplicateCouponClaimMustBeRejected() throws Exception {
        if (!enabled()) { return; }

        long userId = 999_000_004L;
        long couponId;
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.executeUpdate("insert into coupon (code, name, type, amount, threshold, stock, status) "
                    + "values ('IT-COUPON-DUP', '并发重复领券', 'CASH', 100, 0, 100, 'enabled')");
            try (ResultSet r = s.executeQuery("select last_insert_id()")) { r.next(); couponId = r.getLong(1); }
            s.executeUpdate("delete from user_coupon where user_id = " + userId);
        }
        try {
            AtomicInteger ok = new AtomicInteger();
            runConcurrently(() -> {
                try (Connection c = conn();
                     PreparedStatement ps = c.prepareStatement(
                             "insert into user_coupon (user_id, coupon_id, status) values (?, ?, 'UNUSED')")) {
                    ps.setLong(1, userId);
                    ps.setLong(2, couponId);
                    ps.executeUpdate();
                    ok.incrementAndGet();
                } catch (Exception ignored) { }
            });

            int count;
            try (Connection c = conn(); Statement s = c.createStatement();
                 ResultSet r = s.executeQuery(
                         "select count(*) from user_coupon where user_id = " + userId
                                 + " and coupon_id = " + couponId + " and status in ('UNUSED','LOCKED') and deleted = 0")) {
                r.next(); count = r.getInt(1);
            }
            assertEquals(1, count, "同一用户同一券只能持有一张有效券（唯一索引保证）");
            assertEquals(1, ok.get(), "只有 1 次领取成功");
        } finally {
            try (Connection c = conn(); Statement s = c.createStatement()) {
                s.executeUpdate("delete from user_coupon where user_id = " + userId);
                s.executeUpdate("delete from coupon where id = " + couponId);
            }
        }
    }
}
