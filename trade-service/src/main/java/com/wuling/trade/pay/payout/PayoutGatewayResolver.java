package com.wuling.trade.pay.payout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 出款通道选型器（提现 → 微信商家转账 / 易宝自助结算）。
 *
 * <p>与 {@code PaymentGatewayResolver} 同构：按 {@code app.pay.payout-channel}
 * （缺省回落到 {@code app.pay.channel}）从所有 {@link PayoutGateway} 实现中挑一个，
 * 新增通道只需加 {@code @Component}。
 *
 * <p><b>为什么要独立的 payout-channel</b>：收款通道与出款通道会长期并存不同组合
 * （如微信收款 + 易宝出款）。若两者共用一个开关，切换出款就必须改动收款通道，
 * 而收款通道的 Bean 被历史订单退款依赖，改配置会导致退款失败。
 *
 * <p><b>fail-fast</b>：配置的通道找不到实现时启动即失败（而非静默回退 mock），
 * 避免「以为在打款，实际在演戏」的资损级问题。
 *
 * <p><b>mock 语义</b>：默认 mock 通道下 {@link MockPayoutGateway} 立即返回成功，
 * 维持提现「同步到账」的既有记账行为；切 wxpay / yeepay 才走真实出款。
 */
@Component
public class PayoutGatewayResolver {

    private static final Logger log = LoggerFactory.getLogger(PayoutGatewayResolver.class);
    public static final String DEFAULT_CHANNEL = "MOCK";

    private final Map<String, PayoutGateway> gateways = new HashMap<>();
    private final String activeChannel;

    public PayoutGatewayResolver(List<PayoutGateway> implementations,
                                 @Value("${app.pay.payout-channel:${app.pay.channel:mock}}") String configuredChannel) {
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
