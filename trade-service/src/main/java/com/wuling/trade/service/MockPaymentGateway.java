package com.wuling.trade.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/** Mock 支付通道：用于打通全链路，不产生真实资金流。 */
@Component
public class MockPaymentGateway implements PaymentGateway {

    @Override
    public String channel() {
        return "MOCK";
    }

    @Override
    public String prepay(String orderNo, long amount) {
        return "MOCKPAY" + System.currentTimeMillis() % 100000000L
                + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
    }

    /**
     * Mock 通道没有真实三方订单可查。
     *
     * <p>刻意返回 {@code null} 而不是伪造一个 SUCCESS：后台「异常重试」若在
     * mock 通道下把失败单改成成功，会污染数据、掩盖真实问题。
     * 返回 null 让后台明确提示「通道不支持查询」，语义诚实。
     */
    @Override
    public PaymentQueryResult queryOrder(String orderNo) {
        return null;
    }

    @Override
    public boolean verifyCallback(String payload, String signature) {
        // Mock 通道不做真实验签
        return true;
    }
}
