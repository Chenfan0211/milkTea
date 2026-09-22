package com.wuling.common.config;

import com.wuling.common.security.PaymentCallbackSigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 支付回调签名配置（P0 安全修复）。
 *
 * <p><b>只在声明了该配置的服务中生效</b>：
 * 通过 {@code @ConditionalOnProperty(name = "app.pay.enabled", havingValue = "true")} 控制，
 * 因此只有真正承接支付回调的服务（当前为 trade-service）才需要注入密钥，
 * 其他服务不会因缺少密钥而启动失败。
 *
 * <p><b>fail-fast 语义（重要）</b>：一旦该服务声明了支付能力（{@code app.pay.enabled=true}），
 * 若未注入 {@code PAY_CALLBACK_SECRET} 则<b>拒绝启动</b> ——
 * 绝不能带着「无签名校验」的回调接口上线。
 *
 * <p>为什么不用默认密钥：回调验签是资金安全的最后一道闸门，
 * 写在仓库里的默认密钥等同于完全公开，任何人可伪造回调把订单刷成已支付。
 */
@Configuration
@ConditionalOnProperty(name = "app.pay.enabled", havingValue = "true")
public class PaymentSignConfig {

    @Bean
    public PaymentCallbackSigner paymentCallbackSigner(
            @Value("${app.pay.callback-secret:}") String secret,
            @Value("${app.pay.callback-tolerance-ms:300000}") long toleranceMs) {
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException(
                    "当前服务已启用支付能力（app.pay.enabled=true），但未配置回调密钥 "
                    + "app.pay.callback-secret（环境变量 PAY_CALLBACK_SECRET）。"
                    + "缺失会导致回调接口无法验签，服务拒绝启动。");
        }
        return new PaymentCallbackSigner(secret, toleranceMs);
    }
}
