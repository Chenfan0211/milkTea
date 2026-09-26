package com.wuling.trade.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.MqProducer;
import com.wuling.common.mq.event.OrderVerifiedEvent;
import com.wuling.trade.dto.VerifyRequest;
import com.wuling.trade.entity.Order;
import com.wuling.trade.entity.OrderItem;
import com.wuling.trade.entity.VerifyRecord;
import com.wuling.trade.mapper.OrderItemMapper;
import com.wuling.trade.mapper.OrderMapper;
import com.wuling.trade.mapper.VerifyRecordMapper;
import com.wuling.trade.port.ProductQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VerifyServiceStatusTest {

    private static final String ORDER_NO = "O202609260001";
    private static final String PICKUP_CODE = "P001";

    private OrderMapper orderMapper;
    private OrderItemMapper orderItemMapper;
    private VerifyRecordMapper verifyRecordMapper;
    private OrderService orderService;
    private MqProducer mqProducer;
    private VerifyService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        orderItemMapper = mock(OrderItemMapper.class);
        verifyRecordMapper = mock(VerifyRecordMapper.class);
        orderService = mock(OrderService.class);
        mqProducer = mock(MqProducer.class);
        service = new VerifyService(
                orderMapper,
                orderItemMapper,
                verifyRecordMapper,
                orderService,
                mock(ProductQueryPort.class),
                mqProducer);
    }

    @Test
    void paidOrderCanBeVerified() {
        Order order = order(OrderService.STATUS_PAID, null);
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(orderItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        VerifyService.VerifyResult result = service.verify(request());

        assertTrue(result.isSuccess());
        verify(orderService).markVerified(eq(order), any(LocalDateTime.class));
        verify(verifyRecordMapper).insert(any(VerifyRecord.class));
        verify(mqProducer).send(
                eq(MqConstants.FINANCE_SPLIT_ROUTING_KEY),
                any(OrderVerifiedEvent.class),
                eq(ORDER_NO));
    }

    @Test
    void refundPendingOrderCannotBeVerified() {
        assertRejected(order(OrderService.STATUS_PAID, "PENDING"));
    }

    @Test
    void completedOrderCannotBeVerifiedAgain() {
        assertRejected(order(OrderService.STATUS_COMPLETED, null));
    }

    @Test
    void canceledOrderCannotBeVerified() {
        assertRejected(order(OrderService.STATUS_CANCELED, "REFUNDED"));
    }

    @Test
    void legacyVerifiedStatusCannotBeVerified() {
        assertRejected(order("VERIFIED", null));
    }

    private void assertRejected(Order order) {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.verify(request()));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        verifyNoInteractions(orderService, orderItemMapper, verifyRecordMapper, mqProducer);
        verify(mqProducer, never()).send(any(), any(), any());
    }

    private VerifyRequest request() {
        VerifyRequest request = new VerifyRequest();
        request.setType("ORDER");
        request.setCode(PICKUP_CODE);
        request.setOperator("tester");
        request.setDevice("unit-test");
        return request;
    }

    private Order order(String status, String refundStatus) {
        Order order = new Order();
        order.setId(1L);
        order.setOrderNo(ORDER_NO);
        order.setStoreSubjectId(9L);
        order.setPickupCode(PICKUP_CODE);
        order.setPaidAmount(1000L);
        order.setStatus(status);
        order.setRefundStatus(refundStatus);
        return order;
    }
}