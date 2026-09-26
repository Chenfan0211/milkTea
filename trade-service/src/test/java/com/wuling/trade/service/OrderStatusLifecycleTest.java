package com.wuling.trade.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wuling.common.mq.MqProducer;
import com.wuling.trade.entity.Order;
import com.wuling.trade.mapper.OrderItemMapper;
import com.wuling.trade.mapper.OrderMapper;
import com.wuling.trade.pricing.MemberPricingService;
import com.wuling.trade.port.ProductQueryPort;
import com.wuling.trade.port.SplitSnapshotQueryPort;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderStatusLifecycleTest {

    private OrderMapper orderMapper;
    private OrderService orderService;

    @BeforeAll
    static void initMybatisPlusMetadata() {
        if (TableInfoHelper.getTableInfo(Order.class) == null) {
            MybatisConfiguration configuration = new MybatisConfiguration();
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, Order.class.getName());
            TableInfoHelper.initTableInfo(assistant, Order.class);
        }
    }

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        orderService = new OrderService(
                orderMapper,
                mock(OrderItemMapper.class),
                mock(ProductQueryPort.class),
                mock(SplitSnapshotQueryPort.class),
                mock(MqProducer.class),
                mock(MemberPricingService.class));
        when(orderMapper.updateById(any(Order.class))).thenReturn(1);
    }

    @Test
    void verifySuccessWritesCompletedStatus() {
        Order order = order(OrderService.STATUS_PAID);

        orderService.markVerified(order, LocalDateTime.now());

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).updateById(captor.capture());
        assertEquals(OrderService.STATUS_COMPLETED, captor.getValue().getStatus());
        assertEquals(OrderService.STATUS_COMPLETED, order.getStatus());
    }

    @Test
    void refundSuccessWritesCanceledAndRefundedStatus() {
        Order order = order(OrderService.STATUS_PAID);
        order.setRefundStatus("PENDING");

        orderService.markRefunded(order);

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).updateById(captor.capture());
        assertEquals(OrderService.STATUS_CANCELED, captor.getValue().getStatus());
        assertEquals("REFUNDED", captor.getValue().getRefundStatus());
        assertEquals(OrderService.STATUS_CANCELED, order.getStatus());
        assertEquals("REFUNDED", order.getRefundStatus());
    }

    @Test
    void refundAcceptedKeepsPaidStatusAndWritesPending() {
        Order order = order(OrderService.STATUS_PAID);

        orderService.markRefundPending(order);

        assertEquals(OrderService.STATUS_PAID, order.getStatus());
        assertEquals("PENDING", order.getRefundStatus());
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).updateById(captor.capture());
        assertEquals(null, captor.getValue().getStatus());
        assertEquals("PENDING", captor.getValue().getRefundStatus());
    }

    @Test
    void refundFailureWritesFailedAndKeepsPaidStatus() {
        Order order = order(OrderService.STATUS_PAID);
        order.setRefundStatus("PENDING");

        orderService.markRefundFailed(order);

        assertEquals(OrderService.STATUS_PAID, order.getStatus());
        assertEquals("FAILED", order.getRefundStatus());
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).updateById(captor.capture());
        assertEquals(OrderService.STATUS_PAID, captor.getValue().getStatus());
        assertEquals("FAILED", captor.getValue().getRefundStatus());
    }

    @Test
    void pendingVerifyOrdersExcludeRefundPending() {
        when(orderMapper.selectList(any())).thenReturn(List.of());

        orderService.pendingVerifyOrders(9L);

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(orderMapper).selectList(captor.capture());
        captor.getValue().getTargetSql();
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(OrderService.STATUS_PAID));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("PENDING"));
        assertFalse(captor.getValue().getParamNameValuePairs().containsValue("VERIFIED"));
    }

    private Order order(String status) {
        Order order = new Order();
        order.setId(1L);
        order.setOrderNo("O202609260001");
        order.setStoreSubjectId(9L);
        order.setPickupCode("P001");
        order.setPaidAmount(1000L);
        order.setStatus(status);
        return order;
    }
}