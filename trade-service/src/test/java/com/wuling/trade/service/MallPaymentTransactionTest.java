package com.wuling.trade.service;

import com.wuling.common.alert.AlertChannel;
import com.wuling.common.outbox.OutboxService;
import com.wuling.trade.dto.CreateMallOrderRequest;
import com.wuling.trade.entity.Order;
import com.wuling.trade.entity.OrderItem;
import com.wuling.trade.entity.Payment;
import com.wuling.trade.mapper.OrderItemMapper;
import com.wuling.trade.mapper.OrderMapper;
import com.wuling.trade.mapper.OrderShippingMapper;
import com.wuling.trade.mapper.PaymentMapper;
import com.wuling.trade.pay.wxpay.WxPayTransaction;
import com.wuling.trade.pay.wxpay.WxPayPrepayResult;
import com.wuling.trade.port.MallAddressPort;
import com.wuling.trade.port.MallCatalogPort;
import com.wuling.trade.port.MallLeaderPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises public entry points through Spring's actual transaction interceptor. */
class MallPaymentTransactionTest {
    private final Memory memory = new Memory();
    private final SnapshotTransactions transactions = new SnapshotTransactions(memory);
    private MallCatalogPort catalog;
    private PaymentService service;
    private OrderService orders;
    private OrderMapper orderMapper;
    private OrderItemMapper itemMapper;
    private PaymentMapper paymentMapper;
    private TestGateway gateway;
    private PaymentGatewayResolver resolver;

    @BeforeEach void setup() {
        Order order = new Order();
        order.setId(1L); order.setOrderNo("MO-TRANSACTION"); order.setOrderType("MALL");
        order.setUserId(7L); order.setPaidAmount(1200L); order.setPayChannel("WXPAY");
        order.setStatus("CREATED"); order.setPayStatus("UNPAID");
        order.setCreateTime(LocalDateTime.now());
        memory.order = order;
        Payment payment = new Payment();
        payment.setId(1L); payment.setPaymentNo("PAY-1"); payment.setOrderNo(order.getOrderNo());
        payment.setOrderId(1L); payment.setBizType("ORDER"); payment.setAmount(1200L);
        payment.setChannel("WXPAY"); payment.setStandardStatus("PAYING");
        memory.payment = payment;
        OrderItem item = new OrderItem(); item.setMallSkuId(101L); item.setQuantity(1); item.setOrderId(1L);
        memory.items.add(item);
        orderMapper = mapper(OrderMapper.class, (method, args) -> switch (method) {
            case "selectOne" -> copy(memory.order, Order.class);
            case "insert" -> { memory.order = copy(args[0], Order.class); memory.order.setId(1L);
                ((Order) args[0]).setId(1L); yield 1; }
            case "updateById" -> { Order patch = (Order) args[0];
                if (patch.getStatus() != null) memory.order.setStatus(patch.getStatus());
                if (patch.getPayStatus() != null) memory.order.setPayStatus(patch.getPayStatus()); yield 1; }
            default -> null;
        });
        itemMapper = mapper(OrderItemMapper.class, (method, args) -> switch (method) {
            case "selectList" -> List.copyOf(memory.items);
            case "insert" -> { memory.items.add((OrderItem) args[0]); yield 1; }
            default -> null;
        });
        paymentMapper = mapper(PaymentMapper.class, (method, args) -> switch (method) {
            case "selectOne", "selectCanonicalOrderPayment" -> copy(memory.payment, Payment.class);
            case "selectById" -> memory.allPayments().stream()
                    .filter(row -> row.getId().equals(args[0])).findFirst()
                    .map(row -> copy(row, Payment.class)).orElse(null);
            case "selectPayingBatch" -> memory.allPayments().stream()
                    .filter(row -> "PAYING".equals(row.getStandardStatus()) && row.getId() > (Long) args[0])
                    .sorted(java.util.Comparator.comparing(Payment::getId)).limit((Integer) args[1])
                    .map(row -> copy(row, Payment.class)).toList();
            case "closeSupersededAttempts" -> {
                for (Payment row : memory.historical) {
                    if (row.getOrderNo().equals(args[0]) && !row.getId().equals(args[1])
                            && List.of("PAYING", "FAILED").contains(row.getStandardStatus())) {
                        row.setStandardStatus("CLOSED"); row.setThirdStatus("SUPERSEDED");
                    }
                }
                yield 1;
            }
            case "selectList" -> memory.allPayments().stream().sorted(java.util.Comparator.comparing(Payment::getId))
                    .limit(memory.batchLimit).map(row -> copy(row, Payment.class)).toList();
            case "insert", "updateById" -> {
                if (memory.failPaymentWrite) throw new IllegalStateException("payment storage unavailable");
                memory.payment = copy(args[0], Payment.class); yield 1;
            }
            default -> null;
        });
        catalog = mock(MallCatalogPort.class);
        when(catalog.confirm(anyString(), anyList())).thenReturn(MallCatalogPort.StockResult.ok());
        when(catalog.release(anyString(), anyList())).thenReturn(MallCatalogPort.StockResult.ok());
        MallStockService stock = new MallStockService(itemMapper, catalog);
        orders = proxy(new OrderService(orderMapper, itemMapper, mock(OrderShippingMapper.class),
                mock(com.wuling.trade.port.ProductQueryPort.class), null, null,
                mock(OutboxService.class), null, stock));
        gateway = new TestGateway();
        resolver = new PaymentGatewayResolver(List.of(new MockPaymentGateway(), gateway), "MOCK");
        service = proxy(new PaymentService(orders, paymentMapper, itemMapper, resolver,
                mock(AlertChannel.class), null, null, null, mock(OutboxService.class), null, null, stock));
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("paymentService", service);
        orders.setPaymentServiceProvider(beans.getBeanProvider(PaymentService.class));
    }

    @Test void publicCallbackRollsBackOrderWhenStockConfirmationFailsAndRetryCompletesEverything() {
        when(catalog.confirm(anyString(), anyList())).thenReturn(MallCatalogPort.StockResult.fail("库存暂不可用"));
        assertThatThrownBy(() -> service.routeWxPayCallback(success())).isInstanceOf(RuntimeException.class);
        assertThat(memory.order.getStatus()).isEqualTo("CREATED");
        assertThat(memory.payment.getStandardStatus()).isEqualTo("PAYING");
        when(catalog.confirm(anyString(), anyList())).thenReturn(MallCatalogPort.StockResult.ok());
        service.routeWxPayCallback(success());
        assertThat(memory.order.getStatus()).isEqualTo("PAID");
        assertThat(memory.payment.getStandardStatus()).isEqualTo("PAID");
    }

    @Test void paymentStorageFailureRollsBackOrderAndRepeatedNotificationRepairsPaymentAndStock() {
        memory.failPaymentWrite = true;
        assertThatThrownBy(() -> service.routeWxPayCallback(success())).isInstanceOf(IllegalStateException.class);
        assertThat(memory.order.getStatus()).isEqualTo("CREATED");
        memory.failPaymentWrite = false;
        service.routeWxPayCallback(success());
        assertThat(memory.payment.getTransactionId()).isEqualTo("WX-1");
        assertThat(memory.order.getPayStatus()).isEqualTo("PAID");
    }

    @Test void legacyPaidOrderDoesNotShortCircuitMissingPaymentOrStockConfirmation() {
        memory.order.setStatus("PAID"); memory.order.setPayStatus("PAID");
        when(catalog.confirm(anyString(), anyList())).thenReturn(MallCatalogPort.StockResult.fail("missing confirm"));
        assertThatThrownBy(() -> service.routeWxPayCallback(success())).isInstanceOf(RuntimeException.class);
        assertThat(memory.payment.getStandardStatus()).isEqualTo("PAYING");
        when(catalog.confirm(anyString(), anyList())).thenReturn(MallCatalogPort.StockResult.ok());
        service.routeWxPayCallback(success());
        assertThat(memory.payment.getStandardStatus()).isEqualTo("PAID");
    }

    @Test void externalTwoArgumentCreateRollsBackAllLocalRowsWhenReservationFails() {
        memory.order = null; memory.payment = null; memory.items.clear();
        MallAddressPort address = (userId, addressId) -> new MallAddressPort.AddressView(
                "收货人", "13800000000", "广东", "深圳", "南山", "测试地址");
        MallLeaderPort leaders = userId -> MallLeaderPort.LeaderInfo.none();
        when(catalog.findSkus(anyList())).thenReturn(List.of(new MallCatalogPort.SkuView(
                101L, 11L, "SKU1", "茶杯", "默认", "local", 1200L, 1200L,
                500L, 100L, 0L, 8L, 20, true)));
        when(catalog.reserve(anyString(), anyList())).thenReturn(MallCatalogPort.StockResult.fail("库存并发不足"));
        MallOrderService mall = proxy(new MallOrderService(orderMapper, itemMapper,
                mock(OrderShippingMapper.class), catalog, address, mock(OutboxService.class), orders,
                leaders, mock(MallOrderLookupService.class)));
        CreateMallOrderRequest request = new CreateMallOrderRequest();
        request.setClientRequestId("req-1"); request.setAddressId(1L);
        CreateMallOrderRequest.Item line = new CreateMallOrderRequest.Item(); line.setSkuId(101L); line.setQuantity(1);
        request.setItems(List.of(line));
        assertThatThrownBy(() -> mall.create(7L, request)).isInstanceOf(RuntimeException.class);
        assertThat(memory.order).isNull();
        assertThat(memory.items).isEmpty();
    }

    @Test void manualQuerySuccessSettlesOrderAndStockThroughOriginalChannelEvenAfterConfigurationChanges() {
        memory.payment.setStandardStatus("FAILED");
        gateway.query = query("SUCCESS", 1200L, "MO-TRANSACTION");
        PaymentRetryService retries = new PaymentRetryService(paymentMapper, resolver);
        retries.setPaymentService(service);
        proxy(retries).retry(1L);
        assertThat(memory.order.getStatus()).isEqualTo("PAID");
        assertThat(memory.payment.getStandardStatus()).isEqualTo("PAID");
        assertThat(memory.payment.getTransactionId()).isEqualTo("WX-QUERY-1");
    }

    @Test void querySuccessWithWrongAmountCannotMarkPaymentOrOrderPaid() {
        memory.payment.setStandardStatus("FAILED");
        gateway.query = query("SUCCESS", 1L, "MO-TRANSACTION");
        PaymentRetryService retries = new PaymentRetryService(paymentMapper, resolver);
        retries.setPaymentService(service);
        assertThatThrownBy(() -> proxy(retries).retry(1L)).isInstanceOf(RuntimeException.class);
        assertThat(memory.order.getStatus()).isEqualTo("CREATED");
        assertThat(memory.payment.getStandardStatus()).isEqualTo("FAILED");
    }

    @Test void userCancellationFindsPaidTransactionAndSettlesWithoutReleasingStock() {
        gateway.query = query("SUCCESS", 1200L, "MO-TRANSACTION");
        orders.cancelUnpaid("MO-TRANSACTION", 7L);
        assertThat(memory.order.getStatus()).isEqualTo("PAID");
        assertThat(memory.payment.getStandardStatus()).isEqualTo("PAID");
        assertThat(gateway.closed).isFalse();
    }

    @Test void cancellationWithUnknownQueryDoesNotReleaseStockOrCancelOrder() {
        gateway.query = null;
        assertThatThrownBy(() -> orders.cancelUnpaid("MO-TRANSACTION", 7L)).isInstanceOf(RuntimeException.class);
        assertThat(memory.order.getStatus()).isEqualTo("CREATED");
        assertThat(memory.payment.getStandardStatus()).isEqualTo("PAYING");
        assertThat(gateway.closed).isFalse();
    }

    @Test void timeoutDoesNotCancelWhilePaymentQueryIsUnknown() {
        gateway.query = null;
        assertThat(orders.closeIfUnpaid("MO-TRANSACTION")).isFalse();
        assertThat(memory.order.getStatus()).isEqualTo("CREATED");
    }

    @Test void orderWithoutAnyPrepayCanBeCancelledAndTimedOutWithoutRemoteQuery() {
        memory.payment = null;
        orders.cancelUnpaid("MO-TRANSACTION", 7L);
        assertThat(memory.order.getStatus()).isEqualTo("CANCELED");
        memory.order.setStatus("CREATED");
        assertThat(orders.closeIfUnpaid("MO-TRANSACTION")).isTrue();
        assertThat(memory.order.getStatus()).isEqualTo("CANCELED");
    }

    @Test void callbackMissingTransactionIdCannotCreatePaymentFact() {
        WxPayTransaction invalid = success(); invalid.setTransactionId(null);
        assertThatThrownBy(() -> service.routeWxPayCallback(invalid)).isInstanceOf(RuntimeException.class);
        assertThat(memory.order.getStatus()).isEqualTo("CREATED");
    }

    @Test void callbackWrongCurrencyIsRejectedBeforeFulfilment() {
        WxPayTransaction invalid = success(); invalid.setCurrency("USD");
        assertThatThrownBy(() -> service.routeWxPayCallback(invalid)).isInstanceOf(RuntimeException.class);
        assertThat(memory.order.getStatus()).isEqualTo("CREATED");
    }

    @Test void unpaidCancellationRequiresSuccessfulRemoteClose() {
        gateway.query = query("NOTPAY", 1200L, "MO-TRANSACTION"); gateway.closeSucceeds = false;
        assertThatThrownBy(() -> orders.cancelUnpaid("MO-TRANSACTION", 7L)).isInstanceOf(RuntimeException.class);
        assertThat(memory.order.getStatus()).isEqualTo("CREATED");
        gateway.closeSucceeds = true;
        when(catalog.release(anyString(), anyList())).thenReturn(MallCatalogPort.StockResult.ok());
        orders.cancelUnpaid("MO-TRANSACTION", 7L);
        assertThat(memory.order.getStatus()).isEqualTo("CANCELED");
        assertThat(gateway.closed).isTrue();
    }

    @Test void mallPrepayUsesSameFifteenMinuteDeadlineAsLocalOrder() {
        // 选原支付通道，避免 MOCK 当前配置对历史单造成改道。
        service.prepayForMiniApp("MO-TRANSACTION", 1200L, "test-openid", "测试订单");
        assertThat(gateway.expiry).isEqualTo(memory.order.getCreateTime().plusMinutes(15));
        assertThat(memory.payment.getChannel()).isEqualTo("WXPAY");
        assertThat(memory.payment.getPaymentNo()).isEqualTo("PAY-1");
    }

    @Test void cancelledOrderLateSuccessPreservesMoneyFactWithoutFulfilment() {
        memory.order.setStatus("CANCELED");
        service.routeWxPayCallback(success());
        assertThat(memory.order.getStatus()).isEqualTo("CANCELED");
        assertThat(memory.payment.getStandardStatus()).isEqualTo("PAID");
        assertThat(memory.payment.getTransactionId()).isEqualTo("WX-1");
        verify(catalog, never()).confirm(anyString(), anyList());
    }

    @Test void payingSweepRecoversLostNotificationAndUnknownQueryKeepsInventoryReserved() {
        PaymentReconcileJob job = new PaymentReconcileJob(paymentMapper, service, true, 100);
        gateway.query = null;
        assertThat(job.sweep()).isZero();
        assertThat(memory.order.getStatus()).isEqualTo("CREATED");
        assertThat(memory.payment.getStandardStatus()).isEqualTo("PAYING");
        gateway.query = query("SUCCESS", 1200L, "MO-TRANSACTION");
        assertThat(job.sweep()).isEqualTo(1);
        assertThat(memory.order.getStatus()).isEqualTo("PAID");
        assertThat(memory.payment.getStandardStatus()).isEqualTo("PAID");
    }

    @Test void prepayFailureNeverReturnsCashierParametersToCaller() {
        memory.failPaymentWrite = true;
        java.util.concurrent.atomic.AtomicReference<WxPayPrepayResult> delivered =
                new java.util.concurrent.atomic.AtomicReference<>();
        assertThatThrownBy(() -> delivered.set(service.prepayForMiniApp(
                "MO-TRANSACTION", 1200L, "test-openid", "test")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(delivered.get()).isNull();
        assertThat(gateway.expiry).isEqualTo(memory.order.getCreateTime().plusMinutes(15));
        assertThat(memory.payment.getPrepayId()).isNull();
    }

    @Test void payingSweepRotatesPastMoreThanOneBatchOfUnknownHistoricalAttempts() {
        memory.payment.setId(4L);
        for (long id = 1; id <= 3; id++) {
            Payment historical = copy(memory.payment, Payment.class); historical.setId(id);
            historical.setOrderNo("MO-UNKNOWN-" + id); memory.historical.add(historical);
        }
        gateway.query = query("SUCCESS", 1200L, "MO-TRANSACTION");
        memory.batchLimit = 2;
        PaymentReconcileJob job = new PaymentReconcileJob(paymentMapper, service, true, 2);
        job.sweep();
        assertThat(memory.order.getStatus()).isEqualTo("CREATED");
        job.sweep();
        assertThat(memory.order.getStatus()).isEqualTo("PAID");
        assertThat(memory.payment.getStandardStatus()).isEqualTo("PAID");
        assertThat(memory.historical).allSatisfy(row -> assertThat(row.getStandardStatus()).isEqualTo("PAYING"));
    }

    @Test void historicalAttemptsConvergeToOnePaidCanonicalRecordWithoutDuplicatingMoney() {
        memory.payment.setId(3L);
        for (long id = 1; id <= 2; id++) {
            Payment historical = copy(memory.payment, Payment.class); historical.setId(id);
            memory.historical.add(historical);
        }
        gateway.query = query("SUCCESS", 1200L, "MO-TRANSACTION");
        service.reconcilePayment(1L);
        assertThat(memory.order.getStatus()).isEqualTo("PAID");
        assertThat(memory.allPayments().stream().filter(row -> "PAID".equals(row.getStandardStatus())).count())
                .isEqualTo(1L);
        assertThat(memory.historical).allSatisfy(row -> {
            assertThat(row.getStandardStatus()).isEqualTo("CLOSED");
            assertThat(row.getThirdStatus()).isEqualTo("SUPERSEDED");
        });
    }

    private PaymentGateway.PaymentQueryResult query(String state, long amount, String orderNo) {
        return new PaymentGateway.PaymentQueryResult(state, "test", "WX-QUERY-1", amount,
                orderNo, "CNY", "test-mch", "test-app", "test-openid");
    }

    private WxPayTransaction success() {
        WxPayTransaction tx = new WxPayTransaction(); tx.setOutTradeNo("MO-TRANSACTION");
        tx.setTransactionId("WX-1"); tx.setTotalAmount(1200L); tx.setTradeState("SUCCESS");
        return tx;
    }
    @SuppressWarnings("unchecked") private <T> T proxy(T target) {
        ProxyFactory factory = new ProxyFactory(target); factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
    interface Handler { Object call(String method, Object[] args); }
    @SuppressWarnings("unchecked") private static <T> T mapper(Class<T> type, Handler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> handler.call(method.getName(), args));
    }
    private static <T> T copy(Object source, Class<T> type) {
        if (source == null) return null;
        try { T result = type.getDeclaredConstructor().newInstance(); BeanUtils.copyProperties(source, result); return result; }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static final class Memory {
        Order order; Payment payment; List<OrderItem> items = new ArrayList<>(); boolean failPaymentWrite;
        List<Payment> historical = new ArrayList<>();
        int batchLimit = Integer.MAX_VALUE;
        List<Payment> allPayments() {
            List<Payment> result = new ArrayList<>(historical); if (payment != null) result.add(payment); return result;
        }
    }
    private static final class TestGateway implements PaymentGateway {
        PaymentQueryResult query; boolean closeSucceeds; boolean closed; LocalDateTime expiry;
        public String channel() { return "WXPAY"; }
        public String prepay(String no, long amount) { return "PAY-FAKE"; }
        public PaymentQueryResult queryOrder(String no) { return no.startsWith("MO-UNKNOWN") ? null : query; }
        public boolean closeOrder(String no) { closed = closeSucceeds; return closeSucceeds; }
        public boolean verifyCallback(String payload, String signature) { return true; }
        public WxPayPrepayResult prepayForMiniApp(String no, long amount, String openid,
                                                 String description, LocalDateTime expireTime) {
            expiry = expireTime; WxPayPrepayResult result = new WxPayPrepayResult();
            result.setPrepayId("prepay-test"); return result;
        }
    }
    private static final class SnapshotTransactions extends AbstractPlatformTransactionManager {
        final Memory memory; boolean active; Order orderBefore; Payment paymentBefore; List<OrderItem> itemsBefore;
        SnapshotTransactions(Memory memory) { this.memory = memory; }
        @Override protected Object doGetTransaction() { return this; }
        @Override protected boolean isExistingTransaction(Object transaction) { return active; }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            active = true; orderBefore = copy(memory.order, Order.class);
            paymentBefore = copy(memory.payment, Payment.class); itemsBefore = new ArrayList<>(memory.items);
        }
        @Override protected void doCommit(DefaultTransactionStatus status) { active = false; }
        @Override protected void doSetRollbackOnly(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) {
            memory.order = orderBefore; memory.payment = paymentBefore; memory.items = itemsBefore; active = false;
        }
    }
}
