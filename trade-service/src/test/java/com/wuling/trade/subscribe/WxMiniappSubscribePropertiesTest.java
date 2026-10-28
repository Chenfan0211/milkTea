package com.wuling.trade.subscribe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 商城发货订阅模板配置校验测试。
 *
 * <p>背景：发货模板 ID 缺失时后端原本完全静默跳过（不报错、不告警），
 * 导致“用户永远收不到发货通知”这一故障无法被发现。
 * 本测试锁住两点：缺失模板不阻断启动（不抛异常），但会走告警分支；
 * 下单模板缺失仍为致命错误。
 */
class WxMiniappSubscribePropertiesTest {

    @Test
    void missingMallShippedTemplateDoesNotBlockStartup() {
        WxMiniappSubscribeProperties properties = new WxMiniappSubscribeProperties();
        properties.setAppId("wx-test");
        properties.setAppSecret("secret-test");
        properties.getMallShippedSubscribe().setTemplateId("");

        assertDoesNotThrow(properties::validate, "发货模板缺失不应阻断服务启动");
    }

    @Test
    void missingOrderTemplateStillFailsFast() {
        WxMiniappSubscribeProperties properties = new WxMiniappSubscribeProperties();
        properties.setAppId("wx-test");
        properties.setAppSecret("secret-test");
        properties.getOrderSubscribe().setTemplateId("");

        assertThrows(IllegalStateException.class, properties::validate,
                "下单模板缺失必须 fail-fast");
    }

    @Test
    void configuredMallShippedTemplatePasses() {
        WxMiniappSubscribeProperties properties = new WxMiniappSubscribeProperties();
        properties.setAppId("wx-test");
        properties.setAppSecret("secret-test");
        properties.getMallShippedSubscribe().setTemplateId("tpl-mall-shipped");

        assertDoesNotThrow(properties::validate);
    }
}
