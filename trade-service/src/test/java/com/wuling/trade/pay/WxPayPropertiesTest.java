package com.wuling.trade.pay;

import com.wuling.trade.pay.wxpay.WxPayProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 微信支付配置 fail-fast 测试（第 14 期）。
 *
 * <p>核心断言：<b>mock 通道下必须完全不校验</b>（保证备案前可正常启动），
 * 而 <b>wxpay 通道下缺配置必须拒绝启动</b>（避免带着无签名能力的通道上线）。
 */
class WxPayPropertiesTest {

    @Test
    void mock通道下不校验任何配置() {
        WxPayProperties props = new WxPayProperties();
        props.setChannel("mock");
        // 全部敏感项为空，也应通过校验（本地开发 / CI / 备案前状态）
        assertDoesNotThrow(props::validateOnStartup);
    }

    @Test
    void wxpay通道缺少商户号时拒绝启动() {
        WxPayProperties props = new WxPayProperties();
        props.setChannel("wxpay");
        IllegalStateException e = assertThrows(IllegalStateException.class, props::validateOnStartup);
        assertTrue(e.getMessage().contains("WXPAY_MCH_ID"),
                "报错信息必须带出环境变量名，便于上线排查，实际：" + e.getMessage());
    }

    @Test
    void wxpay通道apiV3密钥长度非32位时拒绝启动() {
        WxPayProperties props = validPublicKeyProps();
        props.setApiV3Key("too-short");
        IllegalStateException e = assertThrows(IllegalStateException.class, props::validateOnStartup);
        assertTrue(e.getMessage().contains("32"), "应提示密钥长度要求，实际：" + e.getMessage());
    }

    @Test
    void wxpay通道回调用http地址时拒绝启动() {
        WxPayProperties props = validPublicKeyProps();
        // 微信要求回调地址必须 https（且域名需备案）
        props.setNotifyUrl("http://43.136.91.239:8089/api/v1/app/payments/wxpay/notify");
        IllegalStateException e = assertThrows(IllegalStateException.class, props::validateOnStartup);
        assertTrue(e.getMessage().contains("https"), "应提示必须 https，实际：" + e.getMessage());
    }

    @Test
    void wxpay通道私钥文件不可读时拒绝启动() {
        WxPayProperties props = validPublicKeyProps();
        props.setPrivateKeyPath("/opt/wuling/app/certs/not-exist.pem");
        IllegalStateException e = assertThrows(IllegalStateException.class, props::validateOnStartup);
        assertTrue(e.getMessage().contains("不可读"), "应提示文件不可读，实际：" + e.getMessage());
    }

    @Test
    void wxpay通道验签模式非法时拒绝启动() {
        WxPayProperties props = validPublicKeyProps();
        props.setVerifyMode("something-else");
        assertThrows(IllegalStateException.class, props::validateOnStartup);
    }

    /** 构造一份「除被改字段外全部合法」的 public-key 模式配置 */
    private WxPayProperties validPublicKeyProps() {
        WxPayProperties props = new WxPayProperties();
        props.setChannel("wxpay");
        props.setMchId("1900000001");
        props.setAppId("wx7d78d69cd175812e");
        props.setApiV3Key("0123456789abcdef0123456789abcdef");
        props.setMerchantSerialNo("ABCDEF1234567890");
        // 用 JDK 自带的一个一定存在的文件代替证书，只验证「可读性」逻辑
        props.setPrivateKeyPath(System.getProperty("java.home") + "/release");
        props.setVerifyMode("public-key");
        props.setPublicKeyId("PUB_KEY_ID_0000000000000000000000000000000001");
        props.setPublicKeyPath(System.getProperty("java.home") + "/release");
        props.setNotifyUrl("https://api.wulingshiguang.top/api/v1/app/payments/wxpay/notify");
        return props;
    }
}
