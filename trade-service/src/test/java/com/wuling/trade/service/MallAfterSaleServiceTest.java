package com.wuling.trade.service;

import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.trade.dto.CreateAfterSaleRequest;
import com.wuling.trade.dto.MallAfterSaleVO;
import com.wuling.trade.entity.MallAfterSale;
import com.wuling.trade.entity.Order;
import com.wuling.trade.entity.OrderItem;
import com.wuling.trade.entity.Refund;
import com.wuling.trade.mapper.MallAfterSaleMapper;
import com.wuling.trade.mapper.OrderItemMapper;
import com.wuling.trade.mapper.OrderMapper;
import com.wuling.trade.mapper.RefundMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MallAfterSaleServiceTest {

    private static final Long USER_ID = 88L;
    private static final String ORDER_NO = "MO202610270001";
    private static final Long ORDER_ID = 200L;
    private static final Long ITEM_ID = 301L;

    private OrderMapper orderMapper;
    private OrderItemMapper orderItemMapper;
    private MallAfterSaleMapper afterSaleMapper;
    private RefundMapper refundMapper;
    private RefundService refundService;
    private MallAfterSaleService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        orderItemMapper = mock(OrderItemMapper.class);
        afterSaleMapper = mock(MallAfterSaleMapper.class);
        refundMapper = mock(RefundMapper.class);
        refundService = mock(RefundService.class);
        service = new MallAfterSaleService(orderMapper, orderItemMapper, afterSaleMapper, refundService, refundMapper);
        when(afterSaleMapper.selectActiveByOrderIdForUpdate(ORDER_ID)).thenReturn(null);
        when(afterSaleMapper.selectSumRefundedQuantity(ORDER_ID, ITEM_ID)).thenReturn(0);
        doAnswer(invocation -> {
            MallAfterSale afterSale = invocation.getArgument(0);
            afterSale.setId(401L);
            return 1;
        }).when(afterSaleMapper).insert(any(MallAfterSale.class));
    }

    @Test
    @DisplayName("发货前订单不能申请售后，必须走旧的整单退款链路")
    void createRejectsOrderBeforeShipment() {
        when(orderMapper.selectOne(any())).thenReturn(order("PAID"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(USER_ID, ORDER_NO, request()));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
    }

    @Test
    @DisplayName("只能为自己的商城订单申请售后")
    void createRejectsForeignOrder() {
        when(orderMapper.selectOne(any())).thenReturn(order("SHIPPED"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(USER_ID + 1, ORDER_NO, request()));

        assertEquals(ResultCode.FORBIDDEN, ex.getCode());
    }

    @Test
    @DisplayName("部分数量按订单明细单价计算，不信任客户端金额")
    void createAllowsPartialQuantityAndComputesAmountFromSnapshots() {
        when(orderMapper.selectOne(any())).thenReturn(order("SHIPPED"));
        when(orderItemMapper.selectList(any())).thenReturn(List.of(orderItem(2)));

        MallAfterSaleVO result = service.create(USER_ID, ORDER_NO, request());

        assertEquals(9_900L, result.getRefundAmount());
        ArgumentCaptor<MallAfterSale> captor = ArgumentCaptor.forClass(MallAfterSale.class);
        verify(afterSaleMapper).insert(captor.capture());
        assertEquals("PENDING", captor.getValue().getStatus());
        assertEquals(9_900L, captor.getValue().getRefundAmount());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getItems())
                .contains("\"quantity\":1")
                .contains("\"unitPrice\":9900");
    }

    @Test
    @DisplayName("退货数量不能超过本次剩余可退数量")
    void createRejectsQuantityAboveRemainingRefundableQuantity() {
        when(orderMapper.selectOne(any())).thenReturn(order("SHIPPED"));
        when(orderItemMapper.selectList(any())).thenReturn(List.of(orderItem(2)));
        when(afterSaleMapper.selectSumRefundedQuantity(ORDER_ID, ITEM_ID)).thenReturn(1);

        CreateAfterSaleRequest request = request();
        request.getItems().get(0).setQuantity(2);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(USER_ID, ORDER_NO, request));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
    }

    @Test
    @DisplayName("同一订单同一时刻只能有一个处理中售后单")
    void createRejectsSecondActiveAfterSale() {
        when(orderMapper.selectOne(any())).thenReturn(order("SHIPPED"));
        MallAfterSale active = new MallAfterSale();
        active.setStatus("REFUNDING");
        when(afterSaleMapper.selectActiveByOrderIdForUpdate(ORDER_ID)).thenReturn(active);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(USER_ID, ORDER_NO, request()));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
    }

    @Test
    @DisplayName("审核通过创建关联退款并进入退款中")
    void approveCreatesRefundThroughRefundServiceAndMovesToRefunding() {
        MallAfterSale pending = pendingAfterSale();
        Order order = order("SHIPPED");
        Refund refund = new Refund();
        refund.setId(501L);
        refund.setRefundNo("RF202610270001");
        when(afterSaleMapper.selectByAfterSaleNoForUpdate("AS001")).thenReturn(pending);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(refundService.refundAfterSale(eq(order), eq(pending), nullable(String.class))).thenReturn(refund);

        MallAfterSaleVO result = service.approve("AS001", "admin", "同意退货");

        assertEquals("REFUNDING", result.getStatus());
        assertEquals(501L, result.getRefundId());
        verify(afterSaleMapper).updateById(any(MallAfterSale.class));
        verify(refundService).refundAfterSale(eq(order), eq(pending), anyString());
    }

    @Test
    @DisplayName("拒绝售后只允许待审核，并记录审核人")
    void rejectRequiresPendingAndWritesReviewer() {
        MallAfterSale pending = pendingAfterSale();
        when(afterSaleMapper.selectByAfterSaleNoForUpdate("AS001")).thenReturn(pending);

        MallAfterSaleVO result = service.reject("AS001", "admin", "不符合退货条件");

        assertEquals("REJECTED", result.getStatus());
        assertEquals("admin", result.getReviewer());
        verify(afterSaleMapper).updateById(any(MallAfterSale.class));
    }


    @Test
    @DisplayName("储值退款同步成功时售后单直接置成功，不能覆盖回退款中")
    void approveKeepsSuccessWhenStoredValueRefundCompletesSynchronously() {
        MallAfterSale pending = pendingAfterSale();
        pending.setId(503L);
        pending.setAfterSaleNo("AS002");
        Order order = order("SHIPPED");
        Refund refund = new Refund();
        refund.setId(502L);
        refund.setRefundNo("RF-SUCCESS");
        refund.setStatus(RefundService.STATUS_SUCCESS);
        when(afterSaleMapper.selectByAfterSaleNoForUpdate("AS002")).thenReturn(pending);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(refundService.refundAfterSale(eq(order), eq(pending), any())).thenReturn(refund);
        when(refundMapper.selectById(anyLong())).thenReturn(refund);

        MallAfterSaleVO result = service.approve("AS002", "admin", null);

        assertEquals("SUCCESS", result.getStatus());
        assertNotNull(result.getCompleteTime());
    }

    @Test
    @DisplayName("驳回必须填写原因")
    void rejectRequiresRemark() {
        when(afterSaleMapper.selectByAfterSaleNoForUpdate("AS001")).thenReturn(pendingAfterSale());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reject("AS001", "admin", "  "));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
    }

    @Test
    @DisplayName("用户不能查看他人的售后单")
    void detailRejectsAnotherUsersAfterSale() {
        when(afterSaleMapper.selectByAfterSaleNo("AS001")).thenReturn(pendingAfterSale());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.detail(USER_ID + 1, "AS001"));

        assertEquals(ResultCode.FORBIDDEN, ex.getCode());
    }

    private MallAfterSale pendingAfterSale() {
        MallAfterSale afterSale = new MallAfterSale();
        afterSale.setId(401L);
        afterSale.setAfterSaleNo("AS001");
        afterSale.setOrderId(ORDER_ID);
        afterSale.setOrderNo(ORDER_NO);
        afterSale.setUserId(USER_ID);
        afterSale.setStatus("PENDING");
        afterSale.setRefundAmount(9_900L);
        afterSale.setItems("[]");
        return afterSale;
    }

    private Order order(String status) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderNo(ORDER_NO);
        order.setUserId(USER_ID);
        order.setOrderType("MALL");
        order.setStatus(status);
        order.setPaidAmount(19_800L);
        return order;
    }

    private OrderItem orderItem(int quantity) {
        OrderItem item = new OrderItem();
        item.setId(ITEM_ID);
        item.setOrderId(ORDER_ID);
        item.setMallSkuId(101L);
        item.setProductName("青瓷茶杯");
        item.setSpecSnapshot("{\"value\":\"500ml\"}");
        item.setImage("/images/cup.jpg");
        item.setUnitPrice(9_900L);
        item.setDistributionCommission(100L);
        item.setQuantity(quantity);
        item.setSubTotal(9_900L * quantity);
        return item;
    }

    private CreateAfterSaleRequest request() {
        CreateAfterSaleRequest.Item line = new CreateAfterSaleRequest.Item();
        line.setOrderItemId(ITEM_ID);
        line.setQuantity(1);
        CreateAfterSaleRequest request = new CreateAfterSaleRequest();
        request.setReason("尺码不合适");
        request.setItems(List.of(line));
        return request;
    }
}
