package com.wuling.marketing.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.marketing.entity.GiftCard;
import com.wuling.marketing.entity.GiftCardDenomination;
import com.wuling.marketing.entity.GiftCardOrder;
import com.wuling.marketing.entity.GiftCardRefund;
import com.wuling.marketing.mapper.GiftCardDenominationMapper;
import com.wuling.marketing.mapper.GiftCardMapper;
import com.wuling.marketing.mapper.GiftCardOrderMapper;
import com.wuling.marketing.mapper.GiftCardRefundMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GiftCardPaymentLifecycleTest {

    private final GiftCardDenominationMapper denominationMapper = mock(GiftCardDenominationMapper.class);
    private final GiftCardMapper giftCardMapper = mock(GiftCardMapper.class);
    private final GiftCardOrderMapper orderMapper = mock(GiftCardOrderMapper.class);
    private final GiftCardRefundMapper refundMapper = mock(GiftCardRefundMapper.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final GiftCardService service = new GiftCardService(
            denominationMapper, giftCardMapper, orderMapper, refundMapper, jdbcTemplate, 15L);

    @BeforeAll
    static void initMybatisPlusMetadata() {
        for (Class<?> type : List.of(GiftCard.class, GiftCardOrder.class, GiftCardRefund.class)) {
            if (TableInfoHelper.getTableInfo(type) == null) {
                MybatisConfiguration configuration = new MybatisConfiguration();
                MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, type.getName());
                TableInfoHelper.initTableInfo(assistant, type);
            }
        }
    }

    @Test
    @DisplayName("购买礼品卡使用后端售价并创建待支付订单，不提前发卡")
    void purchaseCreatesUnpaidOrderWithSalePriceAndNoCard() {
        GiftCardDenomination denomination = new GiftCardDenomination();
        denomination.setId(11L);
        denomination.setStatus("enabled");
        denomination.setAmount(10000L);
        denomination.setSalePrice(9000L);
        when(denominationMapper.selectById(11L)).thenReturn(denomination);
        when(orderMapper.insert(any(GiftCardOrder.class))).thenAnswer(invocation -> {
            GiftCardOrder order = invocation.getArgument(0);
            order.setId(101L);
            return 1;
        });

        GiftCardOrder order = service.purchase(7L, 11L);

        assertEquals(9000L, order.getAmount());
        assertEquals("UNPAID", order.getPayStatus());
        assertEquals("CREATED", order.getStatus());
        assertEquals("UNVERIFIED", order.getVerifyStatus());
        assertNotNull(order.getExpireTime());
        verify(giftCardMapper, never()).insert(any(GiftCard.class));
    }

    @Test
    @DisplayName("支付成功后一单一卡，卡通过 order_id 精确绑定订单")
    void settleIssuesExactlyOneCardBoundToOrder() {
        GiftCardOrder order = unpaidOrder();
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(orderMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(giftCardMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(giftCardMapper.insert(any(GiftCard.class))).thenReturn(1);

        GiftCardOrder paid = service.settle("GC202609260001", "WX-TRANS-1", null, 9000L);

        assertEquals("PAID", paid.getPayStatus());
        assertEquals("PAID", paid.getStatus());
        ArgumentCaptor<GiftCard> cardCaptor = ArgumentCaptor.forClass(GiftCard.class);
        verify(giftCardMapper).insert(cardCaptor.capture());
        assertEquals(Long.valueOf(101L), cardCaptor.getValue().getOrderId());
        assertEquals("ACTIVE", cardCaptor.getValue().getStatus());
    }

    @Test
    @DisplayName("重复支付回调不重复发卡")
    void duplicateSettleDoesNotIssueSecondCard() {
        GiftCardOrder order = unpaidOrder();
        order.setPayStatus("PAID");
        order.setStatus("PAID");
        order.setTransactionId("WX-TRANS-1");
        GiftCard existing = new GiftCard();
        existing.setId(201L);
        existing.setOrderId(101L);
        existing.setStatus("ACTIVE");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(giftCardMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        service.settle("GC202609260001", "WX-TRANS-1", null, 9000L);

        verify(giftCardMapper, never()).insert(any(GiftCard.class));
    }

    @Test
    @DisplayName("回调金额与订单金额不一致时拒绝入账")
    void settleRejectsAmountMismatch() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(unpaidOrder());

        assertThrows(RuntimeException.class,
                () -> service.settle("GC202609260001", "WX-TRANS-1", null, 1L));
        verify(giftCardMapper, never()).insert(any(GiftCard.class));
    }

    @Test
    @DisplayName("支付回调缺少金额或交易号时拒绝入账")
    void settleRejectsMissingPaymentEvidence() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(unpaidOrder());

        BusinessException nullAmount = assertThrows(BusinessException.class,
                () -> service.settle("GC202609260001", "WX-TRANS-1", null, null));
        BusinessException blankTransaction = assertThrows(BusinessException.class,
                () -> service.settle("GC202609260001", "   ", null, 9000L));

        assertEquals(ResultCode.BAD_REQUEST, nullAmount.getCode());
        assertEquals(ResultCode.BAD_REQUEST, blankTransaction.getCode());
        verify(orderMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("已取消但未支付的订单收到合法回调后恢复并清除数据库超时标记")
    void settleRestoresCanceledUnpaidOrder() {
        GiftCardOrder order = unpaidOrder();
        order.setStatus("CANCELED");
        order.setCancelType("timeout");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(orderMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(giftCardMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(giftCardMapper.insert(any(GiftCard.class))).thenReturn(1);

        service.settle("GC202609260001", "WX-LATE-1", "openid-7", 9000L);

        assertEquals("PAID", order.getPayStatus());
        assertEquals("PAID", order.getStatus());
        assertNull(order.getCancelType());
        ArgumentCaptor<LambdaUpdateWrapper<GiftCardOrder>> orderUpdateCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(orderMapper).update(any(), orderUpdateCaptor.capture());
        assertTrue(orderUpdateCaptor.getValue().getSqlSet().contains("cancel_type"));
        verify(giftCardMapper).insert(any(GiftCard.class));
    }

    @Test
    @DisplayName("已支付订单缺少交易号时按回调修复")
    void settleRepairsMissingTransactionId() {
        GiftCardOrder order = unpaidOrder();
        order.setPayStatus("PAID");
        order.setStatus("PAID");
        GiftCard existing = new GiftCard();
        existing.setId(201L);
        existing.setOrderId(101L);
        existing.setStatus("ACTIVE");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(orderMapper.update(any(GiftCardOrder.class), any(Wrapper.class))).thenReturn(1);
        when(giftCardMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        service.settle("GC202609260001", "WX-REPAIR-1", null, 9000L);

        assertEquals("WX-REPAIR-1", order.getTransactionId());
        verify(orderMapper).update(any(GiftCardOrder.class), any(Wrapper.class));
    }

    @Test
    @DisplayName("重复支付回调交易号不一致时拒绝")
    void settleRejectsDifferentTransactionId() {
        GiftCardOrder order = unpaidOrder();
        order.setPayStatus("PAID");
        order.setStatus("PAID");
        order.setTransactionId("WX-TRANS-1");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);

        assertThrows(BusinessException.class,
                () -> service.settle("GC202609260001", "WX-OTHER-1", null, 9000L));
        verify(giftCardMapper, never()).insert(any(GiftCard.class));
    }

    @Test
    @DisplayName("核销只使用订单绑定的卡，不会误核销同面额其他卡")
    void verifyUsesOrderBoundCard() {
        GiftCardOrder order = unpaidOrder();
        order.setPayStatus("PAID");
        order.setStatus("PAID");
        GiftCard card = new GiftCard();
        card.setId(201L);
        card.setOrderId(101L);
        card.setStatus("ACTIVE");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(orderMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(giftCardMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(giftCardMapper.selectOne(any(Wrapper.class))).thenReturn(card);

        service.verifyOrder(7L, "GC202609260001");

        assertEquals("COMPLETED", order.getStatus());
        assertEquals("VERIFIED", order.getVerifyStatus());
        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(giftCardMapper).update(any(), captor.capture());
        assertEquals(true, captor.getValue().getSqlSet().contains("status"));
        verify(giftCardMapper).selectOne(any(Wrapper.class));
    }

    @Test
    @DisplayName("退款中的订单禁止核销")
    void verifyRejectsRefundingOrder() {
        GiftCardOrder order = unpaidOrder();
        order.setPayStatus("PAID");
        order.setStatus("PAID");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(refundMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.verifyOrder(7L, "GC202609260001"));

        assertEquals(ResultCode.BAD_REQUEST, exception.getCode());
        assertTrue(exception.getMessage().contains("退款处理中"));
        verify(orderMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("退款受理创建退款记录且订单主状态保持已支付")
    void refundBeginCreatesRecordAndKeepsOrderPaid() {
        GiftCardOrder order = unpaidOrder();
        order.setPayStatus("PAID");
        order.setStatus("PAID");
        order.setTransactionId("WX-TRANS-1");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(refundMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(refundMapper.insert(any(GiftCardRefund.class))).thenAnswer(invocation -> {
            GiftCardRefund refund = invocation.getArgument(0);
            refund.setId(301L);
            return 1;
        });
        when(orderMapper.update(any(), any(Wrapper.class))).thenReturn(1);

        GiftCardRefund refund = service.refundBegin("GC202609260001", "客户申请退款");

        assertEquals("REFUNDING", refund.getStatus());
        assertEquals(9000L, refund.getAmount());
        assertEquals("PAID", order.getStatus());
        assertEquals("refund", order.getCancelType());
        assertEquals(9000L, order.getRefundAmount());

        ArgumentCaptor<LambdaUpdateWrapper<GiftCardOrder>> orderUpdateCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(orderMapper).update(any(), orderUpdateCaptor.capture());
        assertFalse(orderUpdateCaptor.getValue().getSqlSet().contains("status"));
    }

    @Test
    @DisplayName("已有处理中退款记录时拒绝重复发起退款")
    void refundBeginRejectsExistingRefundingRecord() {
        GiftCardOrder order = unpaidOrder();
        order.setPayStatus("PAID");
        order.setStatus("PAID");
        order.setTransactionId("WX-TRANS-1");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(refundMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.refundBegin("GC202609260001", null));

        assertEquals(ResultCode.BAD_REQUEST, exception.getCode());
        assertTrue(exception.getMessage().contains("退款处理中"));
        verify(refundMapper, never()).insert(any(GiftCardRefund.class));
    }

    @Test
    @DisplayName("已核销订单禁止发起退款")
    void refundBeginRejectsVerifiedOrder() {
        GiftCardOrder order = unpaidOrder();
        order.setPayStatus("PAID");
        order.setStatus("PAID");
        order.setVerifyStatus("VERIFIED");
        order.setTransactionId("WX-TRANS-1");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.refundBegin("GC202609260001", null));

        assertEquals(ResultCode.BAD_REQUEST, exception.getCode());
        verify(refundMapper, never()).insert(any(GiftCardRefund.class));
    }

    @Test
    @DisplayName("退款成功将订单置已退款并作废绑定卡")
    void refundConfirmVoidsBoundCard() {
        GiftCardRefund refund = new GiftCardRefund();
        refund.setId(301L);
        refund.setRefundNo("GR202609260001");
        refund.setOrderNo("GC202609260001");
        refund.setAmount(9000L);
        refund.setStatus("REFUNDING");
        GiftCardOrder order = unpaidOrder();
        order.setPayStatus("PAID");
        order.setStatus("PAID");
        order.setTransactionId("WX-TRANS-1");
        GiftCard card = new GiftCard();
        card.setId(201L);
        card.setOrderId(101L);
        card.setStatus("ACTIVE");
        when(refundMapper.selectOne(any(Wrapper.class))).thenReturn(refund);
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(orderMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(refundMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(giftCardMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(giftCardMapper.selectOne(any(Wrapper.class))).thenReturn(card);

        GiftCardRefund result = service.refundConfirm("GC202609260001",
                "GR202609260001", "WX-REFUND-1");

        assertEquals("SUCCESS", result.getStatus());
        assertEquals("REFUNDED", order.getPayStatus());
        assertEquals("CANCELED", order.getStatus());
        assertEquals("VOID", card.getStatus());
    }

    @Test
    @DisplayName("已完成订单禁止确认退款")
    void refundConfirmRejectsCompletedOrder() {
        GiftCardRefund refund = new GiftCardRefund();
        refund.setId(301L);
        refund.setRefundNo("GR202609260001");
        refund.setOrderNo("GC202609260001");
        refund.setAmount(9000L);
        refund.setStatus("REFUNDING");
        GiftCardOrder order = unpaidOrder();
        order.setPayStatus("PAID");
        order.setStatus("COMPLETED");
        order.setTransactionId("WX-TRANS-1");
        when(refundMapper.selectOne(any(Wrapper.class))).thenReturn(refund);
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.refundConfirm(
                        "GC202609260001", "GR202609260001", "WX-REFUND-1"));

        assertEquals(ResultCode.BAD_REQUEST, exception.getCode());
        assertTrue(exception.getMessage().contains("已核销"));
        verify(orderMapper, never()).update(any(), any(Wrapper.class));
        verify(refundMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("退款失败后重试成功仍能完成终态")
    void refundConfirmRetriesFailedRefund() {
        GiftCardRefund refund = new GiftCardRefund();
        refund.setId(301L);
        refund.setRefundNo("GR202609260001");
        refund.setOrderNo("GC202609260001");
        refund.setAmount(9000L);
        refund.setStatus("FAILED");
        refund.setFailReason("银行退票");
        GiftCardOrder order = unpaidOrder();
        order.setPayStatus("PAID");
        order.setStatus("PAID");
        order.setTransactionId("WX-TRANS-1");
        GiftCard card = new GiftCard();
        card.setId(201L);
        card.setOrderId(101L);
        card.setStatus("ACTIVE");
        when(refundMapper.selectOne(any(Wrapper.class))).thenReturn(refund);
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(orderMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(refundMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(giftCardMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(giftCardMapper.selectOne(any(Wrapper.class))).thenReturn(card);

        GiftCardRefund result = service.refundConfirm(
                "GC202609260001", "GR202609260001", "WX-REFUND-RETRY-1");

        assertEquals("SUCCESS", result.getStatus());
        assertNull(result.getFailReason());
        assertEquals("VOID", card.getStatus());
    }

    @Test
    @DisplayName("退款失败保持订单已支付并清空退款辅助字段")
    void refundFailKeepsPaidOrderAndClearsRefundFields() {
        GiftCardRefund refund = new GiftCardRefund();
        refund.setId(301L);
        refund.setRefundNo("GR202609260001");
        refund.setOrderNo("GC202609260001");
        refund.setAmount(9000L);
        refund.setStatus("REFUNDING");
        GiftCardOrder order = unpaidOrder();
        order.setPayStatus("PAID");
        order.setStatus("PAID");
        order.setCancelType("refund");
        order.setRefundAmount(9000L);
        order.setTransactionId("WX-TRANS-1");
        when(refundMapper.selectOne(any(Wrapper.class))).thenReturn(refund);
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(refundMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(orderMapper.update(any(), any(Wrapper.class))).thenReturn(1);

        GiftCardRefund result = service.refundFail(
                "GC202609260001", "GR202609260001", "银行退票");

        assertEquals("FAILED", result.getStatus());
        assertEquals("PAID", order.getStatus());
        ArgumentCaptor<LambdaUpdateWrapper<GiftCardOrder>> orderUpdateCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(orderMapper).update(any(), orderUpdateCaptor.capture());
        String sqlSet = orderUpdateCaptor.getValue().getSqlSet();
        assertFalse(sqlSet.contains("status"));
        assertTrue(sqlSet.contains("cancel_type"));
        assertTrue(sqlSet.contains("refund_amount"));
        assertTrue(orderUpdateCaptor.getValue().getParamNameValuePairs().containsValue(0L));
    }

    @Test
    @DisplayName("内部订单查询返回最新退款记录状态")
    void requireByOrderNoReturnsLatestRefundStatus() {
        GiftCardOrder order = unpaidOrder();
        GiftCardRefund refund = new GiftCardRefund();
        refund.setStatus("FAILED");
        refund.setFailReason("银行退票");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(refundMapper.selectOne(any(Wrapper.class))).thenReturn(refund);

        GiftCardOrder result = service.requireByOrderNo("GC202609260001");

        assertEquals("FAILED", result.getRefundStatus());
        assertEquals("银行退票", result.getRefundFailReason());
    }

    @Test
    @DisplayName("待支付取消按订单号生效，已支付不能走取消接口")
    void cancelOnlyUnpaidOrder() {
        GiftCardOrder order = unpaidOrder();
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(orderMapper.update(any(), any(Wrapper.class))).thenReturn(1);

        GiftCardOrder canceled = service.cancelOrder(7L, "GC202609260001");
        assertEquals("CANCELED", canceled.getStatus());

        order.setPayStatus("PAID");
        order.setStatus("PAID");
        assertThrows(RuntimeException.class,
                () -> service.cancelOrder(7L, "GC202609260001"));
    }

    private GiftCardOrder unpaidOrder() {
        GiftCardOrder order = new GiftCardOrder();
        order.setId(101L);
        order.setOrderNo("GC202609260001");
        order.setUserId(7L);
        order.setDenominationId(11L);
        order.setAmount(9000L);
        order.setPayStatus("UNPAID");
        order.setStatus("CREATED");
        order.setVerifyStatus("UNVERIFIED");
        order.setExpireTime(LocalDateTime.now().plusMinutes(15));
        return order;
    }
}
