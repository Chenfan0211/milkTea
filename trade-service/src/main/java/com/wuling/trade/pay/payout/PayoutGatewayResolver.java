package com.wuling.trade.pay.payout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 出款通道选型器（提现 → 微信商家转账）。
 *
 * <p>与 {@code PaymentGatewayResolver} 同构：按 {@code app.pay.channel} 从所有
 * {@link PayoutGateway} 实现中挑一个，新增通道只需加 {@code @Component}。
 *
 * <p><b>fail-fast</b>：配置的通道找不到实现时启动即失败（而非静默回退 mock），
 * 避免「以为在打款，实际在演戏」的资损级问题。
 *
 * <p><b>mock 语义</b>：默认 mock 通道下 {@link MockPayoutGateway} 立即返回成功，
 * 维持提现「同步到账」的既有记账行为；切 wxpay 才走真实转账。
 */
@Component
public class PayoutGatewayResolver {

    private static final Logger log = LoggerFactory.getLogger(PayoutGatewayResolver.class);
    public static final String DEFAULT_CHANNEL = "MOCK";

    private final Map<String, PayoutGateway> gateways = new HashMap<>();
    private final String activeChannel;

    public PayoutGatewayResolver(List<PayoutGateway> implementations,
                                 @Value("${app.pay.channel:mock}") String configuredChannel) {
        for (PayoutGateway gateway : implementations) {
            String key = gateway.channel().toUpperCase();
            if (gateways.containsKey(key)) {
                throw new IllegalStateException("存在重复的出款通道实现：" + key);
            }
            gateways.put(key, gateway);
        }
        String target = configuredChannel == null || configuredChannel.isBlank()
                ? DEFAULT_CHANNEL
                : configuredChannel.trim().toUpperCase();
        PayoutGateway selected = gateways.get(target);
        if (selected == null) {
            throw new IllegalStateException(
                    "配置的出款通道 app.pay.channel=" + configuredChannel + " 没有对应实现，服务拒绝启动。"
                    + "已注册通道：" + gateways.keySet());
        }
        this.activeChannel = target;
        log.info("出款通道已选定 channel={} 已注册通道={}", activeChannel, gateways.keySet());
    }

    public PayoutGateway active() {
        return gateways.get(activeChannel);
    }

    public String activeChannel() {
        return activeChannel;
    }

    public boolean isMockChannel() {
        return DEFAULT_CHANNEL.equals(activeChannel);
    }
}
