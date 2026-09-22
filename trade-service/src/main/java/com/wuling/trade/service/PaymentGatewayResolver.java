package com.wuling.trade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 支付通道选型器（第 14 期支付接入）。
 *
 * <p><b>为什么需要它</b>：{@code PaymentService} 原先直接注入
 * {@code MockPaymentGateway}，因此无法在不改业务代码的前提下切换通道。
 * 本类按 {@code app.pay.channel} 从所有 {@link PaymentGateway} 实现中挑一个，
 * 新增通道只需加一个 {@code @Component}，业务代码零改动。
 *
 * <p><b>fail-fast</b>：配置的通道找不到对应实现时<b>启动即失败</b>，
 * 而不是静默回退到 mock —— 静默回退的后果是「以为在收钱，实际在演戏」，
 * 属资损级问题。
 */
@Component
public class PaymentGatewayResolver {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayResolver.class);

    /** 默认通道：未配置时走 Mock，保证本地开发与 CI 无需商户资质 */
    public static final String DEFAULT_CHANNEL = "MOCK";

    private final Map<String, PaymentGateway> gateways = new HashMap<>();
    private final String activeChannel;

    public PaymentGatewayResolver(List<PaymentGateway> implementations,
                                  @Value("${app.pay.channel:mock}") String configuredChannel) {
        for (PaymentGateway gateway : implementations) {
            String key = gateway.channel().toUpperCase();
            if (gateways.containsKey(key)) {
                throw new IllegalStateException(
                        "存在重复的支付通道实现：" + key + "。请确认只有一个实现类声明了该 channel()。");
            }
            gateways.put(key, gateway);
        }

        String target = configuredChannel == null || configuredChannel.isBlank()
                ? DEFAULT_CHANNEL
                : configuredChannel.trim().toUpperCase();

        PaymentGateway selected = gateways.get(target);
        if (selected == null) {
            throw new IllegalStateException(
                    "配置的支付通道 app.pay.channel=" + configuredChannel + " 没有对应实现，服务拒绝启动。"
                    + "已注册的通道：" + gateways.keySet() + "。");
        }
        this.activeChannel = target;
        log.info("支付通道已选定 channel={} 已注册通道={}", activeChannel, gateways.keySet());
    }

    /** 当前生效的通道实现 */
    public PaymentGateway active() {
        return gateways.get(activeChannel);
    }

    /** 当前通道标识（大写，如 MOCK / WXPAY） */
    public String activeChannel() {
        return activeChannel;
    }

    /** 是否为 mock 通道（用于日志与页面提示，避免把模拟当真实） */
    public boolean isMockChannel() {
        return DEFAULT_CHANNEL.equals(activeChannel);
    }
}
