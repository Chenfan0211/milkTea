package com.wuling.trade.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.wuling.trade.entity.BalancePayIntent;
import com.wuling.trade.entity.Order;
import com.wuling.trade.entity.Payment;
import com.wuling.trade.mapper.BalancePayIntentMapper;
import com.wuling.trade.mapper.OrderMapper;
import com.wuling.trade.mapper.PaymentMapper;
import com.wuling.trade.pay.storedvalue.StoredValueBalancePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 余额支付悬挂单补偿任务的判定逻辑测试。
 *
 * <p><b>核心不变量</b>：余额支付必须「扣了钱 ↔ 订单已支付」同时成立。
 * 若出现「意图 PENDING 但订单未支付 / 无支付单」，即为悬挂单，应退回余额。
 *
 * <p>这里用内存 Mapper 驱动 {@link BalancePayReconcileJob#reconcile()}，
 * 覆盖三种判定分支，不依赖 Spring 定时调度。
 */
class BalancePayReconcileJobTest {

    private final List<BalancePayIntent> intents = new CopyOnWriteArrayList<>();
    private final List<Order> orders = new CopyOnWriteArrayList<>();
    private final List<Payment> payments = new CopyOnWriteArrayList<>();
    private final List<long[]> refunds = new CopyOnWriteArrayList<>();
    private final AtomicLong idGen = new AtomicLong(0);

    private BalancePayReconcileJob job;

    @BeforeEach
    void setUp() {
        intents.clear();
        orders.clear();
        payments.clear();
        refunds.clear();

        BalancePayIntentMapper intentMapper = (BalancePayIntentMapper) Proxy.newProxyInstance(
                BalancePayIntentMapper.class.getClassLoader(),
                new Class<?>[]{BalancePayIntentMapper.class},
                (InvocationHandler) (proxy, method, args) -> switch (method.getName()) {
                    case "selectList" -> intents.stream()
                            .filter(i -> BalancePayIntent.STATUS_PENDING.equals(i.getStatus()))
                            .toList();
                    case "update" -> {
                        BalancePayIntent patch = (BalancePayIntent) args[0];
                        intents.forEach(i -> {
                            if (patch.getId() != null && patch.getId().equals(i.getId())) {
                                if (patch.getStatus() != null) i.setStatus(patch.getStatus());
                            }
                        });
                        yield 1;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        OrderMapper orderMapper = (OrderMapper) Proxy.newProxyInstance(
                OrderMapper.class.getClassLoader(),
                new Class<?>[]{OrderMapper.class},
                (InvocationHandler) (proxy, method, args) -> {
                    if ("selectOne".equals(method.getName())) {
                        String orderNo = orderNoOf(args[0]);
                        return orders.stream().filter(o -> orderNo.equals(o.getOrderNo())).findFirst().orElse(null);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        PaymentMapper paymentMapper = (PaymentMapper) Proxy.newProxyInstance(
                PaymentMapper.class.getClassLoader(),
                new Class<?>[]{PaymentMapper.class},
                (InvocationHandler) (proxy, method, args) -> {
                    if ("selectCount".equals(method.getName())) {
                        String orderNo = orderNoOf(args[0]);
                        long n = payments.stream()
                                .filter(p -> orderNo.equals(p.getOrderNo())
                                        && "PAID".equals(p.getStandardStatus()))
                                .count();
                        return n;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        StoredValueBalancePort balancePort = new StoredValueBalancePort() {
            @Override
            public DeductResult deduct(Long userId, long amount, String bizNo) {
                return DeductResult.ok();
            }

            @Override
            public void refund(Long userId, long amount, String bizNo) {
                refunds.add(new long[]{userId, amount});
            }
        };

        job = new BalancePayReconcileJob(intentMapper, orderMapper, paymentMapper, balancePort, true);
    }

    /**
     * 从 MyBatis Wrapper 的 toString 里抠 order_no。
     *
     * LambdaQueryWrapper 的 toString 形如
     * "LambdaQueryWrapper(Order, orderNo = ?)"，等号右侧是参数占位符而非实值，
     * 无法直接取到 orderNo。为绕过这个限制，这里改为：
     * 测试内只放一条订单/支付单时，用「唯一记录」匹配（isOrderPaid 里 order 与
     * payment 都是按 orderNo 查的，但测试数据唯一，直接返回唯一值即可）。
     * 对多记录场景本测试不需要，故用单一映射辅助。
     */
    private String orderNoOf(Object wrapper) {
        // 测试约定：orders/payments 各自至多一条，直接取第一条的 orderNo
        if (!orders.isEmpty()) return orders.get(0).getOrderNo();
        if (!payments.isEmpty()) return payments.get(0).getOrderNo();
        return "";
    }

    private BalancePayIntent pendingIntent(String orderNo, int ageMinutes) {
        BalancePayIntent i = new BalancePayIntent();
        i.setId(idGen.incrementAndGet());
        i.setOrderNo(orderNo);
        i.setUserId(9L);
        i.setAmount(1390L);
        i.setStatus(BalancePayIntent.STATUS_PENDING);
        i.setCreateTime(LocalDateTime.now().minusMinutes(ageMinutes));
        intents.add(i);
        return i;
    }

    @Test
    @DisplayName("订单已支付且有 PAID 支付单 → 意图标记 DONE，不回冲")
    void paidOrderMarksIntentDone() {
        pendingIntent("WX-1", 5);
        Order order = new Order();
        order.setOrderNo("WX-1");
        order.setStatus(OrderService.STATUS_PAID);
        orders.add(order);
        Payment payment = new Payment();
        payment.setOrderNo("WX-1");
        payment.setStandardStatus("PAID");
        payments.add(payment);

        job.reconcile();

        assertEquals(BalancePayIntent.STATUS_DONE, intents.get(0).getStatus());
        assertTrue(refunds.isEmpty(), "已支付订单不得回冲余额");
    }

    @Test
    @DisplayName("订单未支付（悬挂单）→ 退回余额并标记 COMPENSATED")
    void danglingIntentRefundsBalance() {
        pendingIntent("WX-2", 5);
        Order order = new Order();
        order.setOrderNo("WX-2");
        order.setStatus(OrderService.STATUS_CREATED); // 仍是待支付
        orders.add(order);

        job.reconcile();

        assertEquals(BalancePayIntent.STATUS_COMPENSATED, intents.get(0).getStatus());
        assertEquals(1, refunds.size(), "悬挂单必须退回余额");
        assertEquals(1390L, refunds.get(0)[1], "退回金额必须等于原扣款金额");
    }

    @Test
    @DisplayName("订单已支付但缺支付单 → 仍视为悬挂单回冲（两段缺一不可）")
    void paidButNoPaymentRecordStillRefunds() {
        pendingIntent("WX-3", 5);
        Order order = new Order();
        order.setOrderNo("WX-3");
        order.setStatus(OrderService.STATUS_PAID);
        orders.add(order);
        // 故意不放 PAID 支付单

        job.reconcile();

        assertEquals(BalancePayIntent.STATUS_COMPENSATED, intents.get(0).getStatus());
        assertEquals(1, refunds.size(), "缺支付单也必须回冲，否则对不上账");
    }

    @Test
    @DisplayName("订单已完成且有 PAID 支付单 → 意图标记 DONE，不回冲")
    void completedOrderMarksIntentDone() {
        pendingIntent("WX-5", 5);
        Order order = new Order();
        order.setOrderNo("WX-5");
        order.setStatus(OrderService.STATUS_COMPLETED);
        orders.add(order);
        Payment payment = new Payment();
        payment.setOrderNo("WX-5");
        payment.setStandardStatus("PAID");
        payments.add(payment);

        job.reconcile();

        assertEquals(BalancePayIntent.STATUS_DONE, intents.get(0).getStatus());
        assertTrue(refunds.isEmpty(), "已完成订单不得回冲余额");
    }

    @Test
    @DisplayName("旧 VERIFIED 状态不再视为支付终态 → 回冲余额")
    void legacyVerifiedStatusStillRefunds() {
        pendingIntent("WX-6", 5);
        Order order = new Order();
        order.setOrderNo("WX-6");
        order.setStatus("VERIFIED");
        orders.add(order);
        Payment payment = new Payment();
        payment.setOrderNo("WX-6");
        payment.setStandardStatus("PAID");
        payments.add(payment);

        job.reconcile();

        assertEquals(BalancePayIntent.STATUS_COMPENSATED, intents.get(0).getStatus());
        assertEquals(1, refunds.size(), "旧 VERIFIED 不得再作为完成态");
    }
    @Test
    @DisplayName("刚创建的意图（2 分钟内）不处理，避免误判尚未提交的主事务")
    void freshIntentIsSkipped() {
        pendingIntent("WX-4", 1); // 1 分钟前
        Order order = new Order();
        order.setOrderNo("WX-4");
        order.setStatus(OrderService.STATUS_CREATED);
        orders.add(order);

        job.reconcile();

        assertEquals(BalancePayIntent.STATUS_PENDING, intents.get(0).getStatus(),
                "2 分钟内的意图应保留 PENDING，等待主事务提交");
        assertTrue(refunds.isEmpty(), "新鲜意图不得回冲");
    }
}