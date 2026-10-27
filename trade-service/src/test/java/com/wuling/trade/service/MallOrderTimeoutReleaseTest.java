package com.wuling.trade.service;

import com.wuling.common.outbox.OutboxService;
import com.wuling.trade.entity.Order;
import com.wuling.trade.mapper.OrderItemMapper;
import com.wuling.trade.mapper.OrderMapper;
import com.wuling.trade.mapper.OrderShippingMapper;
import com.wuling.trade.pricing.MemberPricingService;
import com.wuling.trade.port.CouponPort;
import com.wuling.trade.port.ProductQueryPort;
import com.wuling.trade.port.SplitSnapshotQueryPort;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MallOrderTimeoutReleaseTest {
    @Test
    void timeoutCloseReleasesReservedStock() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        MallStockService stockService = mock(MallStockService.class);
        Order order = new Order();
        order.setId(1L);
        order.setOrderNo("MO1");
        order.setOrderType("MALL");
        order.setStatus(OrderService.STATUS_CREATED);
        order.setPayStatus("UNPAID");
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.updateById(any(Order.class))).thenReturn(1);
        OrderService service = new OrderService(orderMapper, mock(OrderItemMapper.class),
                mock(OrderShippingMapper.class), mock(ProductQueryPort.class),
                mock(SplitSnapshotQueryPort.class), mock(CouponPort.class),
                mock(OutboxService.class), mock(MemberPricingService.class), stockService);

        boolean closed = service.closeIfUnpaid("MO1");

        assertThat(closed).isTrue();
        verify(stockService).releaseForCancelled(order);
    }
}
