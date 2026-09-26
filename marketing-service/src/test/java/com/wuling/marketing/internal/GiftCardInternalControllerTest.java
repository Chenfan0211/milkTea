package com.wuling.marketing.internal;

import com.wuling.marketing.entity.GiftCardOrder;
import com.wuling.marketing.service.GiftCardService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GiftCardInternalControllerTest {

    @Test
    void findByOrderNoReturnsLatestRefundStatus() {
        GiftCardService service = mock(GiftCardService.class);
        GiftCardOrder order = new GiftCardOrder();
        order.setOrderNo("GC202609260001");
        order.setUserId(7L);
        order.setDenominationId(11L);
        order.setAmount(9000L);
        order.setPayStatus("PAID");
        order.setStatus("PAID");
        order.setVerifyStatus("UNVERIFIED");
        order.setRefundStatus("REFUNDING");
        when(service.requireByOrderNo("GC202609260001")).thenReturn(order);

        Map<String, Object> body = new GiftCardInternalController(service)
                .findByOrderNo("GC202609260001");

        assertEquals("REFUNDING", body.get("refundStatus"));
    }
}
