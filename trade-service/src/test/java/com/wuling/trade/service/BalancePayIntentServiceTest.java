package com.wuling.trade.service;

import com.wuling.common.exception.BusinessException;
import com.wuling.trade.entity.BalancePayIntent;
import com.wuling.trade.mapper.BalancePayIntentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 余额支付意图生命周期测试。
 *
 * <p><b>为什么必须有</b>：意图记录是「扣款-落库」补偿对账的根 ——
 * 若它登记失败或幂等写错，补偿任务要么漏掉悬挂单（钱白扣），
 * 要么重复登记（重复扣款）。这里覆盖：
 * <ol>
 *   <li>首次登记生成 PENDING 意图；</li>
 *   <li>同订单重复登记复用而非新建（防并发重复扣款）；</li>
 *   <li>已 DONE / COMPENSATED 的订单再次登记必须拒绝（状态机防线）；</li>
 *   <li>markDone / markCompensated 正确流转状态。</li>
 * </ol>
 */
class BalancePayIntentServiceTest {

    private final Map<Long, BalancePayIntent> store = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong(0);
    private BalancePayIntentMapper mapper;
    private BalancePayIntentService service;

    @BeforeEach
    void setUp() {
        store.clear();
        mapper = (BalancePayIntentMapper) Proxy.newProxyInstance(
                BalancePayIntentMapper.class.getClassLoader(),
                new Class<?>[]{BalancePayIntentMapper.class},
                (InvocationHandler) (proxy, method, args) -> switch (method.getName()) {
                    case "insert" -> {
                        BalancePayIntent intent = (BalancePayIntent) args[0];
                        long id = idGen.incrementAndGet();
                        intent.setId(id);
                        store.put(id, intent);
                        yield 1;
                    }
                    case "selectOne" -> {
                        // 简化：按 orderNo 从内存查（测试里只有这一个查询维度）
                        String orderNo = extractOrderNo(args);
                        yield orderNo == null ? null
                                : store.values().stream()
                                        .filter(i -> orderNo.equals(i.getOrderNo()))
                                        .findFirst().orElse(null);
                    }
                    case "updateById" -> {
                        BalancePayIntent patch = (BalancePayIntent) args[0];
                        BalancePayIntent cur = store.get(patch.getId());
                        if (cur != null) {
                            if (patch.getStatus() != null) cur.setStatus(patch.getStatus());
                        }
                        yield 1;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        service = new BalancePayIntentService(mapper);
    }

    private String extractOrderNo(Object[] args) {
        // selectOne 的入参是 Wrapper；从 toString 里抠 order_no 太脆弱，
        // 改为维护一个「当前查询 orderNo」的辅助 —— 这里退而求其次：
        // 因为测试只测「同一 orderNo 的复用/拒绝」，直接用内存里唯一一条。
        // 为实现可测，下面测试都用「内存里只放一条」的约束。
        return store.values().stream().map(BalancePayIntent::getOrderNo).findFirst().orElse(null);
    }

    @Test
    @DisplayName("首次登记生成 PENDING 意图")
    void firstRegisterCreatesPendingIntent() {
        BalancePayIntent intent = service.register("WX-1", 9L, 1390L);
        assertNotNull(intent.getId(), "必须落库拿到主键");
        assertEquals(BalancePayIntent.STATUS_PENDING, intent.getStatus());
        assertEquals(1390L, intent.getAmount());
    }

    @Test
    @DisplayName("同订单重复登记复用原意图（防并发重复扣款）")
    void duplicateRegisterReusesExistingIntent() {
        BalancePayIntent first = service.register("WX-1", 9L, 1390L);
        BalancePayIntent second = service.register("WX-1", 9L, 1390L);
        assertEquals(first.getId(), second.getId(), "同订单必须复用同一意图，避免重复扣款");
        assertEquals(BalancePayIntent.STATUS_PENDING, second.getStatus());
    }

    @Test
    @DisplayName("已 DONE 的订单再次登记必须拒绝")
    void registerAfterDoneMustThrow() {
        BalancePayIntent intent = service.register("WX-1", 9L, 1390L);
        intent.setStatus(BalancePayIntent.STATUS_DONE);
        assertThrows(BusinessException.class, () -> service.register("WX-1", 9L, 1390L));
    }

    @Test
    @DisplayName("已 COMPENSATED 的订单再次登记必须拒绝")
    void registerAfterCompensatedMustThrow() {
        BalancePayIntent intent = service.register("WX-1", 9L, 1390L);
        intent.setStatus(BalancePayIntent.STATUS_COMPENSATED);
        assertThrows(BusinessException.class, () -> service.register("WX-1", 9L, 1390L));
    }

    @Test
    @DisplayName("markDone 把 PENDING 流转为 DONE")
    void markDoneTransitionsStatus() {
        BalancePayIntent intent = service.register("WX-1", 9L, 1390L);
        service.markDone("WX-1");
        assertEquals(BalancePayIntent.STATUS_DONE, store.get(intent.getId()).getStatus());
    }

    @Test
    @DisplayName("markCompensated 把 PENDING 流转为 COMPENSATED")
    void markCompensatedTransitionsStatus() {
        BalancePayIntent intent = service.register("WX-1", 9L, 1390L);
        service.markCompensated("WX-1");
        assertEquals(BalancePayIntent.STATUS_COMPENSATED, store.get(intent.getId()).getStatus());
    }
}