package com.wuling.trade.pay;

import com.wuling.common.exception.BusinessException;
import com.wuling.trade.entity.Payment;
import com.wuling.trade.mapper.PaymentMapper;
import com.wuling.trade.pay.wxpay.WxPayStatusMapper;
import com.wuling.trade.service.PaymentGateway;
import com.wuling.trade.service.PaymentGatewayResolver;
import com.wuling.trade.service.PaymentRetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 支付「异常重试」的前置校验与回写口径测试。
 *
 * <p><b>为什么必须有这些用例</b>：重试是资金相关动作，两类错误都会造成损失：
 * <ol>
 *   <li>把「重试」写成直接置成功 → 用户未付款却交付商品（伪造交易）；</li>
 *   <li>对「支付中」的单允许重试 → 与用户正在进行的收银台操作打架，
 *       可能把用户刚支付成功的单覆盖成旧状态。</li>
 * </ol>
 * 故这里在不依赖 Spring / 微信密钥的前提下，覆盖：
 * 状态白名单、三方结果驱动的回写、查不到时保持原状态。
 */
class PaymentRetryGuardTest {

    /** 内存版 PaymentMapper：只实现本用例用到的方法 */
    private Payment stored;

    private PaymentMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = (PaymentMapper) Proxy.newProxyInstance(
                PaymentMapper.class.getClassLoader(),
                new Class<?>[]{PaymentMapper.class},
                (InvocationHandler) (proxy, method, args) -> switch (method.getName()) {
                    case "selectById" -> stored;
                    case "updateById" -> {
                        // 模拟持久化：直接改内存对象即可
                        yield 1;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private PaymentRetryService service(PaymentGateway.PaymentQueryResult queryResult) {
        PaymentGateway gateway = (PaymentGateway) Proxy.newProxyInstance(
                PaymentGateway.class.getClassLoader(),
                new Class<?>[]{PaymentGateway.class},
                (InvocationHandler) (proxy, method, args) -> switch (method.getName()) {
                    case "channel" -> "STUB";
                    case "queryOrder" -> queryResult;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return new PaymentRetryService(mapper, new PaymentGatewayResolver(List.of(gateway), "STUB"));
    }

    private Payment payment(String standardStatus) {
        Payment p = new Payment();
        p.setId(1L);
        p.setOrderNo("20260926000001");
        p.setChannel("STUB");
        p.setStandardStatus(standardStatus);
        p.setThirdStatus("PAYERROR");
        stored = p;
        return p;
    }

    @Test
    @DisplayName("三方返回 SUCCESS 时，标准状态回写为 PAID 并记录流水订单号")
    void successDrivesPaidStatus() {
        payment("FAILED");
        Payment result = service(new PaymentGateway.PaymentQueryResult(
                "SUCCESS", "支付成功", "WX-TX-001", 1390L))
                .retry(1L);

        assertEquals(WxPayStatusMapper.STANDARD_PAID, result.getStandardStatus());
        assertEquals("SUCCESS", result.getThirdStatus(), "third_status 保留三方原始值");
        assertEquals("WX-TX-001", result.getTransactionId(), "流水订单号取自三方");
    }

    @Test
    @DisplayName("三方仍为 PAYERROR 时，不得改写成成功（防伪造交易）")
    void failureMustNotBeRewrittenAsSuccess() {
        payment("FAILED");
        Payment result = service(new PaymentGateway.PaymentQueryResult(
                "PAYERROR", "支付失败", null, 1390L))
                .retry(1L);

        assertEquals(WxPayStatusMapper.STANDARD_FAILED, result.getStandardStatus(),
                "三方未成功时重试必须保持失败，否则等于伪造已支付");
        assertEquals("PAYERROR", result.getThirdStatus());
    }

    @Test
    @DisplayName("三方返回 CLOSED 时回写为已关闭")
    void closedIsMapped() {
        payment("FAILED");
        Payment result = service(new PaymentGateway.PaymentQueryResult(
                "CLOSED", "订单已关闭", null, 1390L))
                .retry(1L);

        assertEquals(WxPayStatusMapper.STANDARD_CLOSED, result.getStandardStatus());
    }

    @Test
    @DisplayName("查不到三方状态时抛错并保持原状态，不得静默改成成功")
    void nullQueryResultMustThrowAndKeepStatus() {
        Payment p = payment("FAILED");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service(null).retry(1L));
        assertTrue(ex.getMessage().contains("未查询到三方订单状态"), ex.getMessage());
        assertEquals(WxPayStatusMapper.STANDARD_FAILED, p.getStandardStatus(),
                "查询失败时状态必须保持原样");
    }

    @Test
    @DisplayName("三方返回未知状态时抛错，不得把未知当成成功")
    void unknownTradeStateMustThrow() {
        payment("FAILED");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service(new PaymentGateway.PaymentQueryResult(
                        "SOME_NEW_STATE", "未知", null, 1390L)).retry(1L));
        assertTrue(ex.getMessage().contains("未知交易状态"), ex.getMessage());
    }

    @Test
    @DisplayName("仅 FAILED 可重试：PAYING / PAID / REFUNDED / CLOSED 必须被拒绝")
    void onlyFailedIsRetryable() {
        for (String status : List.of("PAYING", "PAID", "REFUNDED", "CLOSED")) {
            payment(status);
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service(new PaymentGateway.PaymentQueryResult(
                            "SUCCESS", "支付成功", "WX-TX-002", 1390L)).retry(1L),
                    status + " 不应被允许重试");
            assertTrue(ex.getMessage().contains("仅支付失败"), ex.getMessage());
        }
    }
}