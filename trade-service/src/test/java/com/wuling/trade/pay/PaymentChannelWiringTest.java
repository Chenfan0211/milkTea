package com.wuling.trade.pay;

import com.wuling.trade.pay.wxpay.WxPayNotifyController;
import com.wuling.trade.pay.wxpay.WxPayNotifyService;
import com.wuling.trade.pay.wxpay.WxPayProperties;
import com.wuling.trade.pay.wxpay.WxPaySdkConfig;
import com.wuling.trade.service.MockPaymentGateway;
import com.wuling.trade.service.PaymentGatewayResolver;
import com.wuling.trade.service.WechatPayGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 支付通道条件装配测试（第 14 期）。
 *
 * <p><b>这是「代码写好但不调用」的核心验证</b>：
 * 在默认（mock）通道下，微信支付的 Bean 必须<b>完全不存在</b> ——
 * 不读取证书、不向 api.mch.weixin.qq.com 发起任何请求，
 * 从而保证对现有链路零影响。
 *
 * <p>用 {@link ApplicationContextRunner} 而非 {@code @SpringBootTest}：
 * 本用例只关心「条件装配规则」，引入完整上下文会连带拉起数据源/MQ/Redis，
 * 既慢又与测试意图无关（且本地无中间件时会直接失败）。
 */
class PaymentChannelWiringTest {

    /** 只注册与支付通道选型相关的类 */
    private ApplicationContextRunner runner(String channel) {
        return new ApplicationContextRunner()
                .withUserConfiguration(WxPaySdkConfig.class)
                .withBean(WxPayProperties.class)
                .withBean(MockPaymentGateway.class)
                .withPropertyValues("app.pay.channel=" + channel,
                        "app.pay.wxpay.channel=" + channel);
    }

    @Test
    void mock通道下微信支付Bean全部不存在() {
        runner("mock").run(context -> {
            // SDK 配置类整体不生效 => 不读证书、不请求微信
            assertThat(context).doesNotHaveBean(WxPaySdkConfig.class);
            assertThat(context).doesNotHaveBean("wxPayConfig");
            assertThat(context).doesNotHaveBean("wxPayJsapiService");
            assertThat(context).doesNotHaveBean("wxPayHttpClient");
            assertThat(context).doesNotHaveBean("wxPayNotificationParser");
            assertThat(context).doesNotHaveBean(WechatPayGateway.class);
        });
    }

    @Test
    void mock通道下支付通道选型为MOCK() {
        // 额外注册微信网关类，验证「类在 classpath 但条件不满足时不被选中」
        runner("mock")
                .withBean(WechatPayGateway.class, () -> null)
                .withBean(PaymentGatewayResolver.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(PaymentGatewayResolver.class);
                    PaymentGatewayResolver resolver = context.getBean(PaymentGatewayResolver.class);
                    assertThat(resolver.activeChannel()).isEqualTo("MOCK");
                    assertThat(resolver.isMockChannel()).isTrue();
                });
    }

    /**
     * 配置 wxpay 后，SDK 配置类生效，并因缺少真实证书而<b>启动失败</b>。
     *
     * <p>这条断言等价于 fail-fast 的验证：证书缺失时服务不会「假装正常」启动，
     * 而是直接拒绝 —— 这正是我们希望在备案后切通道时看到的失败方式
     * （第一时间暴露配置遗漏，而不是等到收银台唤起失败才发现）。
     *
     * <p>注意：本用例只断言「启动失败且失败点在 wxPayConfig」，
     * 不断言 SDK 的报错文案 —— 实测官方 SDK 在密钥为空时抛出的信息是
     * {@code message: null}（信息量为零）。因此本项目在
     * {@code WxPayProperties#validateOnStartup} 里自己先做一次带
     * 环境变量名的可读校验，见 {@code WxPayPropertiesTest}。
     */
    @Test
    void 配置wxpay但缺证书时启动失败() {
        runner("wxpay").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("wxPayConfig")
                    .as("失败点应落在微信支付 Config 装配上");
        });
    }

    @Test
    void mock通道下配置对象不被校验且不认为是微信通道() {
        runner("mock").run(context -> {
            WxPayProperties props = context.getBean(WxPayProperties.class);
            assertThat(props.isWxPayChannel()).isFalse();
        });
    }
}
