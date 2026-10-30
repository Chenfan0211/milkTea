package com.wuling.trade.service;

import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.common.outbox.OutboxService;
import com.wuling.trade.dto.CreateOrderRequest;
import com.wuling.trade.mapper.OrderItemMapper;
import com.wuling.trade.mapper.OrderMapper;
import com.wuling.trade.port.CouponPort;
import com.wuling.trade.port.ProductQueryPort;
import com.wuling.trade.port.SplitSnapshotQueryPort;
import com.wuling.trade.pricing.MemberPricingService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClosedStoreOrderTest {

    @Test
    void createOrderRejectsClosedStore() {
        ProductQueryPort productPort = mock(ProductQueryPort.class);
        when(productPort.findSubjectName(101L)).thenReturn("停用店");
        when(productPort.findStoreBusinessStatus(101L)).thenReturn("closed");
        OrderService service = new OrderService(mock(OrderMapper.class), mock(OrderItemMapper.class), productPort,
                mock(SplitSnapshotQueryPort.class), mock(CouponPort.class), mock(OutboxService.class),
                mock(MemberPricingService.class));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createOrder(request()));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        assertEquals("门店已停用", ex.getMessage());
    }

    @Test
    void createOrderRejectsStoreWithoutOpenStatus() {
        ProductQueryPort productPort = mock(ProductQueryPort.class);
        when(productPort.findSubjectName(101L)).thenReturn("无档案店");
        when(productPort.findStoreBusinessStatus(101L)).thenReturn(null);
        OrderService service = new OrderService(mock(OrderMapper.class), mock(OrderItemMapper.class), productPort,
                mock(SplitSnapshotQueryPort.class), mock(CouponPort.class), mock(OutboxService.class),
                mock(MemberPricingService.class));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createOrder(request()));

        assertEquals("门店已停用", ex.getMessage());
    }

    private static CreateOrderRequest request() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setUserId(9L);
        request.setStoreSubjectId(101L);
        request.setItems(List.of());
        return request;
    }
}
