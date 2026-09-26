package com.wuling.marketing.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderNoCodecTest {

    @Test
    @DisplayName("真实 WX 订单号虽超过 Long 范围，仍能稳定映射为锁券数字")
    void encodesRealWxOrderNoWithoutOverflow() {
        String orderNo = "WX202609271234567890";

        long value = OrderNoCodec.toLockOrderId(orderNo);

        assertTrue(value > 0);
        assertEquals(value, OrderNoCodec.toLockOrderId(orderNo));
        assertNotEquals(value, OrderNoCodec.toLockOrderId("WX202609271234567891"));
    }
}