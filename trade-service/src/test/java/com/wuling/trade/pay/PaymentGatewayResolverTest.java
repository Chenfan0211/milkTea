package com.wuling.trade.pay;

import com.wuling.common.exception.BusinessException;
import com.wuling.trade.pay.wxpay.WxPayPrepayResult;
import com.wuling.trade.service.MockPaymentGateway;
import com.wuling.trade.service.PaymentGateway;
import com.wuling.trade.service.PaymentGatewayResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 支付通道选型测试（第 14 期）。
 *
 * <p>最关键的两条：
 * <ol>
 *   <li>默认（不配置）必须走 MOCK —— 保证备案前行为与接入前完全一致；</li>
 *   <li>配了不存在的通道必须<b>启动失败</b>，
 *       绝不能静默回退到 mock（否则「以为在收钱，实际在演戏」）。</li>
 * </ol>
 */
class PaymentGatewayResolverTest {

    /** 测试替身：仅实现通道标识，用于验证选型逻辑 */
    private static class FakeGateway implements PaymentGateway {
        private final String channel;

        FakeGateway(String channel) {
            this.channel = channel;
        }

        @Override
        public String channel() {
            return channel;
        }

        @Override
        public String prepay(String orderNo, long amount) {
            return "FAKE";
        }

        @Override
        public boolean verifyCallback(String payload, String signature) {
            return true;
        }

        @Override
        public WxPayPrepayResult prepayForMiniApp(String orderNo, long amountFen,
                                                  String payerOpenid, String description) {
            WxPayPrepayResult result = new WxPayPrepayResult();
            result.setPrepayId("fake-prepay");
            return result;
        }
    }

    @Test
    void 未配置通道时默认走mock() {
        PaymentGatewayResolver resolver = new PaymentGatewayResolver(
                List.of(new MockPaymentGateway(), new FakeGateway("WXPAY")), null);
        assertEquals("MOCK", resolver.activeChannel());
        assertTrue(resolver.isMockChannel());
        assertEquals("MOCK", resolver.active().channel());
    }

    @Test
    void 显式配置wxpay时选中微信通道() {
        PaymentGatewayResolver resolver = new PaymentGatewayResolver(
                List.of(new MockPaymentGateway(), new FakeGateway("WXPAY")), "wxpay");
        assertEquals("WXPAY", resolver.activeChannel());
        assertTrue(!resolver.isMockChannel());
        assertEquals("WXPAY", resolver.active().channel());
    }

    @Test
    void 大小写与空格不敏感() {
        PaymentGatewayResolver resolver = new PaymentGatewayResolver(
                List.of(new MockPaymentGateway(), new FakeGateway("WXPAY")), " WxPay ");
        assertEquals("WXPAY", resolver.activeChannel());
    }

    @Test
    void 配置不存在的通道时拒绝启动() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new PaymentGatewayResolver(List.of(new MockPaymentGateway()), "alipay"));
        assertTrue(e.getMessage().contains("alipay"), e.getMessage());
        // 报错需列出可用通道，便于快速定位拼写错误
        assertTrue(e.getMessage().contains("MOCK"), e.getMessage());
    }

    @Test
    void 通道标识重复时拒绝启动() {
        assertThrows(IllegalStateException.class,
                () -> new PaymentGatewayResolver(
                        List.of(new FakeGateway("WXPAY"), new FakeGateway("WXPAY")), "mock"));
    }

    /** mock 通道的旧接口必须保持可用（现有回归依赖它） */
    @Test
    void mock通道旧接口仍可用() {
        MockPaymentGateway mock = new MockPaymentGateway();
        assertTrue(mock.prepay("WX001", 1000).startsWith("MOCKPAY"));
    }

    /**
     * 微信通道的 prepay(String,long) 必须抛业务异常，而不是返回占位串。
     * 返回假单号会让上游误以为下单成功，掩盖通道选型错误。
     *
     * <p>通过反射调用方法本体，避免构造 Spring 上下文与读取真实证书。
     */
    @Test
    void 微信通道不支持旧prepay接口() throws Exception {
        Class<?> clazz = Class.forName("com.wuling.trade.service.WechatPayGateway");
        Object gateway = newUnsafeInstance(clazz);
        if (gateway == null) {
            // 运行环境不允许 Unsafe 分配时跳过（不影响其他用例的判断力）
            return;
        }
        java.lang.reflect.Method method = clazz.getMethod("prepay", String.class, long.class);
        java.lang.reflect.InvocationTargetException thrown = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> method.invoke(gateway, "WX001", 1000L));
        assertTrue(thrown.getCause() instanceof BusinessException,
                "应抛业务异常，实际：" + thrown.getCause());
    }

    /** 用 Unsafe 绕过构造器创建实例（仅用于验证方法本身的行为） */
    private Object newUnsafeInstance(Class<?> clazz) {
        try {
            java.lang.reflect.Field field = Class.forName("sun.misc.Unsafe")
                    .getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);
            java.lang.reflect.Method allocate = unsafe.getClass()
                    .getMethod("allocateInstance", Class.class);
            return allocate.invoke(unsafe, clazz);
        } catch (Throwable t) {
            return null;
        }
    }
}
