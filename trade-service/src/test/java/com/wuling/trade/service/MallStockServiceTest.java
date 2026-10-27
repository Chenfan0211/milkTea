package com.wuling.trade.service;

import com.wuling.trade.entity.Order;
import com.wuling.trade.entity.MallAfterSale;
import com.wuling.trade.entity.OrderItem;
import com.wuling.trade.mapper.MallAfterSaleMapper;
import com.wuling.trade.mapper.OrderItemMapper;
import com.wuling.trade.port.MallCatalogPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MallStockServiceTest {

    @Test
    void paidOrderConvertsReservedStockUsingItemSnapshot() {
        OrderItemMapper itemMapper = mock(OrderItemMapper.class);
        MallCatalogPort port = mock(MallCatalogPort.class);
        MallStockService service = new MallStockService(itemMapper, port);

        Order order = new Order();
        order.setOrderNo("MO001");
        order.setOrderType("MALL");
        OrderItem item = new OrderItem();
        item.setMallSkuId(101L);
        item.setQuantity(2);
        when(itemMapper.selectList(any())).thenReturn(List.of(item));
        when(port.confirm(eq("MO001"), anyList())).thenReturn(MallCatalogPort.StockResult.ok());

        service.deductForPaid(order);

        verify(port).confirm(eq("MO001"), argThat(lines ->
                lines.size() == 1 && lines.get(0).skuId().equals(101L) && lines.get(0).quantity() == 2));
    }

    @Test
    void storeOrderDoesNotTouchMallStock() {
        OrderItemMapper itemMapper = mock(OrderItemMapper.class);
        MallCatalogPort port = mock(MallCatalogPort.class);
        MallStockService service = new MallStockService(itemMapper, port);
        Order order = new Order();
        order.setOrderType("STORE");
        order.setOrderNo("SO001");

        service.deductForPaid(order);

        verifyNoInteractions(port, itemMapper);
    }
    @Test
    void afterSaleRestoreReturnsCumulativeQuantityAcrossMultipleAfterSales() {
        OrderItemMapper itemMapper = mock(OrderItemMapper.class);
        MallCatalogPort port = mock(MallCatalogPort.class);
        MallAfterSaleMapper afterSaleMapper = mock(MallAfterSaleMapper.class);
        MallStockService service = new MallStockService(itemMapper, port, afterSaleMapper);

        Order order = new Order();
        order.setId(200L);
        order.setOrderNo("MO200");
        order.setOrderType("MALL");
        OrderItem item = new OrderItem();
        item.setId(301L);
        item.setOrderId(200L);
        item.setMallSkuId(101L);
        item.setQuantity(2);
        MallAfterSale afterSale = new MallAfterSale();
        afterSale.setAfterSaleNo("AS200");
        afterSale.setItems("[{\"orderItemId\":301,\"skuId\":101,\"quantity\":1}]");

        when(itemMapper.selectById(301L)).thenReturn(item);
        when(itemMapper.selectList(any())).thenReturn(List.of(item));
        when(port.restore(eq("MO200"), eq("AS200"), anyList())).thenReturn(MallCatalogPort.StockResult.ok());
        when(afterSaleMapper.selectSumRefundedQuantity(200L, 301L)).thenReturn(2);

        Map<Long, Integer> restored = service.restoreForAfterSale(order, afterSale);

        assertThat(restored).containsEntry(301L, 2);
        verify(port).restore(eq("MO200"), eq("AS200"), argThat(lines ->
                lines.size() == 1 && lines.get(0).skuId().equals(101L) && lines.get(0).quantity() == 1));
    }
}
