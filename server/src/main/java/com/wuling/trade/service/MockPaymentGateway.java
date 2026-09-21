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

    @Override
    public boolean verifyCallback(String payload, String signature) {
        // Mock 通道不做真实验签
        return true;
    }
}
