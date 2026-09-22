package com.wuling.trade.pay;

import com.wuling.trade.pay.wxpay.WxPayStatusMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 微信交易状态映射测试（第 14 期）。
 *
 * <p>纯函数测试，不依赖网络与证书，因此在备案前即可完整运行。
 */
class WxPayStatusMapperTest {

    @Test
    void 成功状态映射为已支付() {
        assertEquals(WxPayStatusMapper.STANDARD_PAID, WxPayStatusMapper.toStandardStatus("SUCCESS"));
        assertTrue(WxPayStatusMapper.isSuccess("SUCCESS"));
    }

    @Test
    void 未支付与支付中均映射为支付中() {
        assertEquals(WxPayStatusMapper.STANDARD_PAYING, WxPayStatusMapper.toStandardStatus("NOTPAY"));
        assertEquals(WxPayStatusMapper.STANDARD_PAYING, WxPayStatusMapper.toStandardStatus("USERPAYING"));
    }

    @Test
    void 关闭类状态映射为已关闭() {
        assertEquals(WxPayStatusMapper.STANDARD_CLOSED, WxPayStatusMapper.toStandardStatus("CLOSED"));
        assertEquals(WxPayStatusMapper.STANDARD_CLOSED, WxPayStatusMapper.toStandardStatus("REVOKED"));
    }

    @Test
    void 退款映射为已退款_支付失败映射为失败() {
        assertEquals(WxPayStatusMapper.STANDARD_REFUNDED, WxPayStatusMapper.toStandardStatus("REFUND"));
        assertEquals(WxPayStatusMapper.STANDARD_FAILED, WxPayStatusMapper.toStandardStatus("PAYERROR"));
    }

    @Test
    void 大小写与空格不敏感() {
        assertEquals(WxPayStatusMapper.STANDARD_PAID, WxPayStatusMapper.toStandardStatus(" success "));
    }

    /**
     * 关键安全断言：未知状态必须返回 null，绝不能默认当成成功。
     * 若这里退化成「默认 PAID」，一个伪造/异常状态即可触发入账。
     */
    @Test
    void 未知状态返回null且不算成功() {
        assertNull(WxPayStatusMapper.toStandardStatus("SOMETHING_NEW"));
        assertNull(WxPayStatusMapper.toStandardStatus(null));
        assertFalse(WxPayStatusMapper.isSuccess("SOMETHING_NEW"));
        assertFalse(WxPayStatusMapper.isSuccess(null));
        assertFalse(WxPayStatusMapper.isSuccess("REFUND"));
    }
}
