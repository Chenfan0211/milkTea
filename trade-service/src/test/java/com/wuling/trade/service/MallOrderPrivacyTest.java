package com.wuling.trade.service;

import com.wuling.common.outbox.OutboxService;
import com.wuling.trade.dto.OrderDTO;
import com.wuling.trade.entity.Order;
import com.wuling.trade.entity.OrderItem;
import com.wuling.trade.mapper.OrderItemMapper;
import com.wuling.trade.mapper.OrderMapper;
import com.wuling.trade.mapper.OrderShippingMapper;
import com.wuling.trade.pricing.MemberPricingService;
import com.wuling.trade.port.CouponPort;
import com.wuling.trade.port.ProductQueryPort;
import com.wuling.trade.port.SplitSnapshotQueryPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MallOrderPrivacyTest {
    @Test
    void customerOrderViewDoesNotExposeCostOrPlatformCommission() {
        ProductQueryPort productPort = mock(ProductQueryPort.class);
        when(productPort.findSubjectName(3L)).thenReturn("测试门店");
        OrderService service = new OrderService(mock(OrderMapper.class), mock(OrderItemMapper.class),
                mock(OrderShippingMapper.class), productPort, mock(SplitSnapshotQueryPort.class),
                mock(CouponPort.class), mock(OutboxService.class), mock(MemberPricingService.class));
        Order order = new Order();
        order.setId(1L);
        order.setOrderNo("MO1");
        order.setOrderType("MALL");
        order.setUserId(8L);
        order.setStoreSubjectId(3L);
        order.setStatus(OrderService.STATUS_PAID);
        OrderItem item = new OrderItem();
        item.setProductId("MP1");
        item.setProductName("茶杯");
        item.setQuantity(1);
        item.setUnitPrice(1000L);
        item.setCostPrice(400L);
        item.setPlatformCommission(100L);
        item.setSpecSnapshot("{\"value\":\"500ml\"}");

        OrderDTO dto = service.toDTO(order, List.of(item), false);

        assertThat(dto.getItems()).singleElement().satisfies(view -> {
            assertThat(view.getCostPrice()).isNull();
            assertThat(view.getPlatformCommission()).isNull();
            assertThat(view.getSpec()).isEqualTo("500ml");
        });
    }
}
