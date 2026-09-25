package com.wuling.trade.pay;

import com.wuling.trade.service.PaymentService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 微信支付回调「业务类型路由」测试（第 15 期储值支付接入）。
 *
 * <p><b>为什么这条测试必须有</b>：
 * 储值订单号（CZ 前缀）与点单订单号在同一个微信商户号下流转，
 * 回调只带回 out_trade_no。若路由判断写错，储值回调会被送进
 * 订单入账链路 —— 那里查不到订单，抛异常 → 应答 FAIL → 微信重试仍失败
 * → <b>用户已付款但余额永不入账</b>，属资损级问题，且从日志上
 * 只表现为「支付回调失败」，极难定位。
 *
 * <p>这里只测纯函数式的前缀判断，不依赖 Spring 上下文与微信密钥，
 * 因此可在无商户资质的环境下稳定运行。
 */
class StoredValueCallbackRoutingTest {

    @Test
    void storedValueOrderMustBeRecognizedByPrefix() {
        // 与 marketing 域 StoredValueService#nextNo 的 "CZ" 前缀一致
        assertTrue(PaymentService.isStoredValueOrder("CZ202609231200001234"),
                "储值单号必须被识别为储值业务，否则回调会走错入账链路");
    }

    @Test
    void orderNumberMustNotBeTreatedAsStoredValue() {
        // 点单订单号形如 202609231200001234（纯数字）或带其他前缀
        assertFalse(PaymentService.isStoredValueOrder("202609231200001234"),
                "点单订单号不得被误判为储值单");
        assertFalse(PaymentService.isStoredValueOrder("ORD202609231200001234"),
                "其他前缀订单不得被误判为储值单");
    }

    @Test
    void nullOrderNumberMustBeSafe() {
        // 回调报文异常时 out_trade_no 可能缺失，不得因此抛 NPE
        assertFalse(PaymentService.isStoredValueOrder(null),
                "单号为空时必须安全返回 false，不能抛异常导致回调 500");
    }

    @Test
    void prefixMustNotMatchInTheMiddleOfOrderNumber() {
        // 前缀判断必须锚定开头：中间含 CZ 的订单号不属于储值业务
        assertFalse(PaymentService.isStoredValueOrder("ORDER-CZ-001"),
                "仅开头匹配才算储值单，避免订单号中偶然出现 CZ 被误判");
    }
}
