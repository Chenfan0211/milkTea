package com.wuling.marketing;

import com.wuling.common.exception.BusinessException;
import com.wuling.marketing.service.StoredValueService;
import com.wuling.user.mapper.AppUserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 储值余额「扣款 / 退回」的资金安全测试。
 *
 * <p><b>为什么必须有</b>：余额支付把「本地改数字」换成了真实扣款，一旦口径写错
 * 就是直接的资金问题：
 * <ol>
 *   <li>扣成负数 → 凭空透支平台资金；</li>
 *   <li>金额 &lt;= 0 也放行 → 刷单 / 白拿；</li>
 *   <li>充值退款与余额退回混用 → 用户可把充值金额套现（充值本身不可退）。</li>
 * </ol>
 *
 * <p>这里只测 Service 的参数校验与「依赖返回值决定成败」的分支，
 * 不连库、不依赖 Spring —— 真正的并发安全性由
 * {@code AppUserMapper#addBalance} 的 SQL 条件（balance + delta >= 0）保证，
 * 属于 SQL 层语义，见该方法的注释。
 */
class StoredValueBalanceTest {

    /** 记录 addBalance 的调用，并按预设的受影响行数作答 */
    private AppUserMapper mapperReturning(int affected) {
        return (AppUserMapper) Proxy.newProxyInstance(
                AppUserMapper.class.getClassLoader(),
                new Class<?>[]{AppUserMapper.class},
                (InvocationHandler) (proxy, method, args) -> {
                    if ("addBalance".equals(method.getName())) {
                        return affected;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** 用反射构造 Service：它依赖多个 Mapper，本用例只关心 AppUserMapper 的行为 */
    private StoredValueService serviceWithMapper(AppUserMapper mapper) throws Exception {
        Constructor<?> ctor = Arrays.stream(StoredValueService.class.getConstructors())
                .max((a, b) -> Integer.compare(a.getParameterCount(), b.getParameterCount()))
                .orElseThrow();
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            args[i] = types[i].isAssignableFrom(AppUserMapper.class) ? mapper : nullProxy(types[i]);
        }
        return (StoredValueService) ctor.newInstance(args);
    }

    private Object nullProxy(Class<?> type) {
        if (!type.isInterface()) {
            return null;
        }
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (InvocationHandler) (proxy, method, args) -> null);
    }

    @Test
    @DisplayName("Deduct: 扣款成功（受影响行数 1）不得抛异常")
    void deductSucceedsWhenRowAffected() throws Exception {
        StoredValueService service = serviceWithMapper(mapperReturning(1));
        service.payWithBalance(9L, 1390L, "WX-1");
    }

    @Test
    @DisplayName("Deduct: 余额不足（受影响行数 0）必须抛业务异常，不得静默放过")
    void deductFailsWhenNoRowAffected() throws Exception {
        StoredValueService service = serviceWithMapper(mapperReturning(0));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.payWithBalance(9L, 1390L, "WX-1"));
        assertTrue(ex.getMessage().contains("余额不足"), ex.getMessage());
    }

    @Test
    @DisplayName("Deduct: 金额 <= 0 必须拒绝（防零元/负数刷单）")
    void deductRejectsNonPositiveAmount() throws Exception {
        StoredValueService service = serviceWithMapper(mapperReturning(1));
        assertThrows(BusinessException.class, () -> service.payWithBalance(9L, 0L, "WX-1"));
        assertThrows(BusinessException.class, () -> service.payWithBalance(9L, -100L, "WX-1"));
    }

    @Test
    @DisplayName("Deduct: 用户为空必须拒绝")
    void deductRejectsNullUser() throws Exception {
        StoredValueService service = serviceWithMapper(mapperReturning(1));
        assertThrows(BusinessException.class, () -> service.payWithBalance(null, 100L, "WX-1"));
    }

    @Test
    @DisplayName("Refund: 退回为「加款」，用户不存在（0 行）必须抛错，不能假装成功")
    void refundFailsWhenUserMissing() throws Exception {
        StoredValueService service = serviceWithMapper(mapperReturning(0));
        assertThrows(BusinessException.class, () -> service.refundToBalance(9L, 1390L, "WX-1"));
    }

    @Test
    @DisplayName("Refund: 金额 <= 0 必须拒绝")
    void refundRejectsNonPositiveAmount() throws Exception {
        StoredValueService service = serviceWithMapper(mapperReturning(1));
        assertThrows(BusinessException.class, () -> service.refundToBalance(9L, 0L, "WX-1"));
    }

    @Test
    @DisplayName("扣款与退回的符号必须相反（扣减为负、退回为正）")
    void deductAndRefundUseOppositeSigns() throws Exception {
        AtomicLong capturedDelta = new AtomicLong(Long.MIN_VALUE);
        AppUserMapper mapper = (AppUserMapper) Proxy.newProxyInstance(
                AppUserMapper.class.getClassLoader(),
                new Class<?>[]{AppUserMapper.class},
                (InvocationHandler) (proxy, method, args) -> {
                    if ("addBalance".equals(method.getName())) {
                        capturedDelta.set((Long) args[1]);
                        return 1;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        StoredValueService service = serviceWithMapper(mapper);

        service.payWithBalance(9L, 1390L, "WX-1");
        assertEquals(-1390L, capturedDelta.get(), "余额支付必须扣减（负增量）");

        service.refundToBalance(9L, 1390L, "WX-1");
        assertEquals(1390L, capturedDelta.get(), "退款必须退回余额（正增量）");
    }
}