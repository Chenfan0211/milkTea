package com.wuling.trade.pay.giftcard;

import com.wuling.trade.service.PaymentService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GiftCardCallbackRoutingTest {

    @Test
    void giftCardOrderMustBeRecognizedByGcPrefix() {
        assertTrue(PaymentService.isGiftCardOrder("GC202609260001"));
    }

    @Test
    void otherBusinessOrdersMustNotBeTreatedAsGiftCard() {
        assertFalse(PaymentService.isGiftCardOrder("CZ202609260001"));
        assertFalse(PaymentService.isGiftCardOrder("202609260001"));
        assertFalse(PaymentService.isGiftCardOrder("ORDER-GC-001"));
        assertFalse(PaymentService.isGiftCardOrder(null));
    }
}
