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
    /** 收款通道（新订单）：与出款通道解耦，见 {@link #forCollect()}。 */
    private final String collectChannel;

    /**
     * 兼容旧构造（单元测试与既有调用）。
     *
     * <p>只传一个通道时，收款通道与出款通道一致——
     * 这是接入 {@code collect-channel} 之前的行为。
     */
    public PaymentGatewayResolver(List<PaymentGateway> implementations, String configuredChannel) {
        this(implementations, configuredChannel, null);
    }

    /**
     * 运行期构造函数（Spring 使用）。
     *
     * <p>显式标 {@code @Autowired}：本类有两个构造函数（另一个是给单元测试的
     * 两参便捷版），不标注会让 Spring 无法决定用哪个。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public PaymentGatewayResolver(List<PaymentGateway> implementations,
                                  @Value("${app.pay.channel:mock}") String configuredChannel,
                                  @Value("${app.pay.collect-channel:${app.pay.channel:mock}}")
                                  String configuredCollectChannel) {
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
        // 收款通道：缺省与出款通道一致（保持既有部署行为）
        String collectTarget = configuredCollectChannel == null || configuredCollectChannel.isBlank()
                ? target
                : configuredCollectChannel.trim().toUpperCase();
        if (!gateways.containsKey(collectTarget)) {
            throw new IllegalStateException(
                    "配置的收款通道 app.pay.collect-channel=" + configuredCollectChannel
                            + " 没有对应实现，服务拒绝启动。已注册通道：" + gateways.keySet());
        }
        this.collectChannel = collectTarget;
        log.info("支付通道已选定 channel={} collectChannel={} 已注册通道={}",
                activeChannel, collectChannel, gateways.keySet());
    }

    /** 当前生效的通道实现 */
    public PaymentGateway active() {
        return gateways.get(activeChannel);
    }

    /** 历史支付只能回到当时实际使用的通道，不能跟随当前配置切换。 */
    public PaymentGateway forChannel(String channel) {
        PaymentGateway gateway = channel == null ? null : gateways.get(channel.trim().toUpperCase());
        if (gateway == null) throw new IllegalStateException("支付记录通道不可用，请人工核查");
        return gateway;
    }

    public PaymentGateway forPayment(com.wuling.trade.entity.Payment payment) {
        if (payment == null) throw new IllegalStateException("缺少原支付记录，请人工核查");
        return forChannel(payment.getChannel());
    }

    /** 当前通道标识（大写，如 MOCK / WXPAY） */
    public String activeChannel() {
        return activeChannel;
    }

    /**
     * 新订单用哪个通道收款。
     *
     * <p><b>为什么独立于 {@link #active()}（出款通道）</b>：
     * 收款与出款会长期并存不同组合（如微信收款 + 易宝出款）。
     * 若共用一个开关，切换出款就必须动收款配置，
     * 而收款 Bean 被历史订单退款依赖（{@link #forPayment}），
     * 改配置会导致历史订单退款失败。
     *
     * <p>缺省回落到 {@code app.pay.channel}，保持既有部署行为不变。
     */
    public PaymentGateway forCollect() {
        PaymentGateway gateway = gateways.get(collectChannel);
        if (gateway == null) {
            throw new IllegalStateException(
                    "配置的收款通道 app.pay.collect-channel=" + collectChannel
                            + " 没有对应实现。已注册通道：" + gateways.keySet());
        }
        return gateway;
    }

    /** 当前收款通道标识（大写）。 */
    public String activeCollectChannel() {
        return collectChannel;
    }

    /** 是否为 mock 通道（用于日志与页面提示，避免把模拟当真实） */
    public boolean isMockChannel() {
        return DEFAULT_CHANNEL.equals(activeChannel);
    }
}
