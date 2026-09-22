package com.wuling.trade.pay;

import com.wechat.pay.java.core.notification.NotificationParser;
import com.wuling.trade.pay.wxpay.WxPayNotifyService;
import com.wuling.trade.pay.wxpay.WxPayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 微信支付回调验签服务的「前置校验」测试（第 14 期）。
 *
 * <p>说明：真正的 RSA 验签与 AES-GCM 解密依赖微信侧密钥/公钥，
 * 必须等商户资质与域名就绪后才能联调（见 docs/wechat-pay-integration.md）。
 * 因此这里覆盖的是<b>不依赖真实密钥</b>的部分：参数缺失、时间戳窗口、格式非法。
 * 这些恰恰是最容易被绕过、又最不该出错的地方。
 *
 * <p>构造 {@link NotificationParser} 时传入 null —— 上述前置校验全部在
 * 调用 parser 之前完成，因此不会触发对 null 的解引用。
 */
class WxPayNotifyServiceTest {

    private WxPayNotifyService service;

    @BeforeEach
    void setUp() {
        WxPayProperties props = new WxPayProperties();
        props.setChannel("wxpay");
        props.setMchId("1900000001");
        props.setAppId("wx7d78d69cd175812e");
        props.setNotifyToleranceSeconds(300);
        service = new WxPayNotifyService(props, null);
    }

    @Test
    void 缺少签名头时拒绝() {
        WxPayNotifyService.WxPayNotifyException e = assertThrows(
                WxPayNotifyService.WxPayNotifyException.class,
                () -> service.verifyAndDecrypt(null, "1758523456", "nonce", "sig", "{}"));
        assertTrue(e.getMessage().contains("Wechatpay-Serial"), e.getMessage());
    }

    @Test
    void 缺少请求体时拒绝() {
        assertThrows(WxPayNotifyService.WxPayNotifyException.class,
                () -> service.verifyAndDecrypt("serial", "1758523456", "nonce", "sig", "  "));
    }

    /**
     * 防重放：时间戳严重偏离当前时间的回调必须被拒。
     * 此处时间戳固定为「很久以前」，避免依赖当前时间导致用例不稳定。
     */
    @Test
    void 时间戳超出窗口时拒绝() {
        String staleTimestamp = String.valueOf(System.currentTimeMillis() / 1000L - 3600);
        WxPayNotifyService.WxPayNotifyException e = assertThrows(
                WxPayNotifyService.WxPayNotifyException.class,
                () -> service.verifyAndDecrypt("serial", staleTimestamp, "nonce", "sig", "{}"));
        assertTrue(e.getMessage().contains("窗口"), e.getMessage());
    }

    @Test
    void 时间戳格式非法时拒绝() {
        assertThrows(WxPayNotifyService.WxPayNotifyException.class,
                () -> service.verifyAndDecrypt("serial", "not-a-number", "nonce", "sig", "{}"));
    }

    @Test
    void 时间戳窗口为0时跳过校验但仍在验签阶段失败() {
        WxPayProperties props = new WxPayProperties();
        props.setNotifyToleranceSeconds(0);
        WxPayNotifyService relaxed = new WxPayNotifyService(props, null);

        // 窗口关闭后时间戳不再拦截；由于 parser 为 null，
        // 会走到验签分支并抛出「验签或解密失败」——说明确实越过了时间戳检查
        WxPayNotifyService.WxPayNotifyException e = assertThrows(
                WxPayNotifyService.WxPayNotifyException.class,
                () -> relaxed.verifyAndDecrypt("serial", "1000000000", "nonce", "sig", "{}"));
        assertTrue(e.getMessage().contains("验签"), e.getMessage());
    }
}
