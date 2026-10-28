package com.wuling.trade.service;

import com.wuling.trade.dto.CreateMallOrderRequest;
import com.wuling.trade.dto.MallCheckoutQuoteDTO;
import com.wuling.trade.dto.MallCheckoutQuoteRequest;
import com.wuling.trade.dto.OrderDTO;
import com.wuling.trade.entity.Order;
import com.wuling.trade.entity.OrderItem;
import com.wuling.trade.mapper.OrderItemMapper;
import com.wuling.trade.mapper.OrderMapper;
import com.wuling.trade.mapper.OrderShippingMapper;
import com.wuling.trade.port.MallCatalogPort;
import com.wuling.trade.port.MallAddressPort;
import com.wuling.trade.port.MallRulePort;
import com.wuling.trade.port.MallLeaderPort;
import com.wuling.common.mq.event.SplitRuleSnapshot;
import com.wuling.common.outbox.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MallOrderServiceTest {

    private OrderMapper orderMapper;
    private OrderItemMapper orderItemMapper;
    private OrderShippingMapper shippingMapper;
    private MallCatalogPort catalogPort;
    private MallAddressPort addressPort;
    private MallRulePort rulePort;
    private MallLeaderPort leaderPort;
    private MallOrderLookupService lookupService;
    private OutboxService outboxService;
    private OrderService orderService;
    private MallOrderService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        orderItemMapper = mock(OrderItemMapper.class);
        shippingMapper = mock(OrderShippingMapper.class);
        catalogPort = mock(MallCatalogPort.class);
        addressPort = mock(MallAddressPort.class);
        rulePort = mock(MallRulePort.class);
        leaderPort = mock(MallLeaderPort.class);
        when(leaderPort.resolve(anyLong())).thenReturn(MallLeaderPort.LeaderInfo.none());
        lookupService = mock(MallOrderLookupService.class);
        when(rulePort.snapshot(5L)).thenReturn(new SplitRuleSnapshot(5L, 300, 100, 2000, 10000L, 3000));
        outboxService = mock(OutboxService.class);
        orderService = mock(OrderService.class);
        when(orderService.toDTO(any(Order.class), anyList())).thenAnswer(invocation -> {
            Order saved = invocation.getArgument(0);
            OrderDTO dto = new OrderDTO();
            dto.setOrderType(saved.getOrderType());
            dto.setOrderNo(saved.getOrderNo());
            dto.setPaidAmount(saved.getPaidAmount());
            dto.setDiscountAmount(saved.getDiscountAmount());
            dto.setStoredValueDiscount(saved.getStoredValueDiscount());
            dto.setCouponDiscount(saved.getCouponDiscount());
            dto.setItems(invocation.<java.util.List<OrderItem>>getArgument(1).stream().map(item -> {
                OrderDTO.Item view = new OrderDTO.Item();
                view.setUnitPrice(item.getUnitPrice());
                view.setQuantity(item.getQuantity());
                view.setCostPrice(item.getCostPrice());
                return view;
            }).toList());
            return dto;
        });
        service = new MallOrderService(orderMapper, orderItemMapper, shippingMapper,
                catalogPort, addressPort, outboxService, orderService, rulePort, leaderPort, lookupService);
    }

    @Test
    void createUsesSkuSalePriceWithoutMemberOrStoredValueDiscount() {
        MallAddressPort.AddressView address = new MallAddressPort.AddressView(
                "张三", "13800000000", "广东省", "深圳市", "南山区", "科技园1号");
        when(addressPort.findOwned(88L, 9L)).thenReturn(address);

        MallCatalogPort.SkuView sku = new MallCatalogPort.SkuView(
                101L, 11L, "MP001", "青瓷茶杯", "500ml", "/images/cup.jpg",
                9900L, 12900L, 4000L, 500L, 0L, 7L,
                3L, 4L, 5L, 8, true);
        when(catalogPort.findSkus(List.of(101L))).thenReturn(List.of(sku));
        doAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(200L);
            return 1;
        }).when(orderMapper).insert(any(Order.class));
        when(catalogPort.reserve(eq("MO_TEST"), anyList())).thenReturn(MallCatalogPort.StockResult.ok());

        CreateMallOrderRequest request = new CreateMallOrderRequest();
        request.setClientRequestId("req-001");
        request.setAddressId(9L);
        CreateMallOrderRequest.Item line = new CreateMallOrderRequest.Item();
        line.setSkuId(101L);
        line.setQuantity(2);
        request.setItems(List.of(line));
        request.setPayChannel("WXPAY");

        var dto = service.create(88L, request, "MO_TEST");

        assertThat(dto.getOrderType()).isEqualTo("MALL");
        assertThat(dto.getPaidAmount()).isEqualTo(19800L);
        assertThat(dto.getDiscountAmount()).isZero();
        assertThat(dto.getStoredValueDiscount()).isZero();
        assertThat(dto.getCouponDiscount()).isZero();
        assertThat(dto.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getUnitPrice()).isEqualTo(9900L);
            assertThat(item.getQuantity()).isEqualTo(2);
            assertThat(item.getCostPrice()).isEqualTo(4000L);
        });

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insert(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getStoreSubjectId()).isEqualTo(3L);
        assertThat(orderCaptor.getValue().getChannelSubjectId()).isEqualTo(4L);
        assertThat(orderCaptor.getValue().getSplitRuleId()).isEqualTo(5L);
        assertThat(orderCaptor.getValue().getClientRequestId()).isEqualTo("req-001");
        ArgumentCaptor<OrderItem> itemCaptor = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemMapper).insert(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getSpecSnapshot()).isEqualTo("{\"value\":\"500ml\"}");
        verify(catalogPort).reserve(eq("MO_TEST"), argThat(lines ->
                lines.size() == 1 && lines.get(0).skuId().equals(101L) && lines.get(0).quantity() == 2));
    }

    @Test
    void leaderBuyerPaysSkuSalePriceWithoutDistributionCommission() {
        // 团长身份：即使 SKU 配了分销佣金，团长购买也只付售价，佣金不外分
        MallAddressPort.AddressView address = new MallAddressPort.AddressView(
                "张团长", "13900000000", "广东省", "深圳市", "南山区", "科技园1号");
        when(addressPort.findOwned(88L, 9L)).thenReturn(address);
        when(leaderPort.resolve(88L)).thenReturn(new MallLeaderPort.LeaderInfo(true, 88L, 9L));

        MallCatalogPort.SkuView sku = new MallCatalogPort.SkuView(
                101L, 11L, "MP001", "青瓷茶杯", "500ml", "/images/cup.jpg",
                9900L, 12900L, 4000L, 500L, 100L, 7L,
                3L, 4L, 5L, 8, true);
        when(catalogPort.findSkus(List.of(101L))).thenReturn(List.of(sku));
        doAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(201L);
            return 1;
        }).when(orderMapper).insert(any(Order.class));
        when(catalogPort.reserve(eq("MO_LEADER"), anyList())).thenReturn(MallCatalogPort.StockResult.ok());

        CreateMallOrderRequest request = new CreateMallOrderRequest();
        request.setClientRequestId("req-leader");
        request.setAddressId(9L);
        CreateMallOrderRequest.Item line = new CreateMallOrderRequest.Item();
        line.setSkuId(101L);
        line.setQuantity(2);
        request.setItems(List.of(line));
        request.setPayChannel("WXPAY");

        var dto = service.create(88L, request, "MO_LEADER");

        assertThat(dto.getPaidAmount()).isEqualTo(19800L);
        assertThat(dto.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getUnitPrice()).isEqualTo(9900L);
            assertThat(item.getQuantity()).isEqualTo(2);
        });
        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insert(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getSplitRuleSnapshot()).contains("\"leaderSubjectId\":9");
    }
    @Test

    void duplicateClientRequestIdReturnsExistingOrderWithoutSecondReservation() {

        Order existing = new Order();
        existing.setId(201L);
        existing.setOrderNo("MO_EXISTING");
        existing.setOrderType("MALL");
        when(orderMapper.selectOne(any())).thenReturn(existing);
        when(orderItemMapper.selectList(any())).thenReturn(List.of());

        CreateMallOrderRequest request = new CreateMallOrderRequest();
        request.setClientRequestId("req-existing");
        request.setAddressId(9L);
        CreateMallOrderRequest.Item line = new CreateMallOrderRequest.Item();
        line.setSkuId(101L);
        line.setQuantity(1);
        request.setItems(List.of(line));

        service.create(88L, request, "MO_NEW");

        verify(orderMapper, never()).insert(any(Order.class));
        verifyNoInteractions(addressPort);
        verifyNoInteractions(catalogPort);
    }

    @Test
    void quoteUsesServerSideLeaderPricingAndPayableTotal() {
        MallCatalogPort.SkuView sku = new MallCatalogPort.SkuView(
                101L, 11L, "MP001", "青瓷茶杯", "500ml", "/images/cup.jpg",
                9900L, 12900L, 4000L, 500L, 100L, 7L,
                3L, 4L, 5L, 8, true);
        when(catalogPort.findSkus(List.of(101L))).thenReturn(List.of(sku));
        when(leaderPort.resolve(88L)).thenReturn(MallLeaderPort.LeaderInfo.none());

        MallCheckoutQuoteRequest request = new MallCheckoutQuoteRequest();
        MallCheckoutQuoteRequest.Item line = new MallCheckoutQuoteRequest.Item();
        line.setSkuId(101L);
        line.setQuantity(2);
        request.setItems(List.of(line));

        MallCheckoutQuoteDTO quote = service.quote(88L, request);

        assertThat(quote.getPayableAmount()).isEqualTo(20000L);
        // 非团长：售价 9900 + 佣金 100 = 10000/件；佣金合计 100 x 2 = 200
        assertThat(quote.getCommissionAmount()).isEqualTo(200L);
        assertThat(quote.getLeaderBuyer()).isFalse();
        assertThat(quote.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getUnitPrice()).isEqualTo(10000L);
            assertThat(item.getDistributionCommission()).isEqualTo(100L);
            assertThat(item.getLineAmount()).isEqualTo(20000L);
            assertThat(item.getValid()).isTrue();
        });
    }

    @Test
    void quoteMarksOutOfStockAsInvalidWithoutChargingIt() {
        MallCatalogPort.SkuView sku = new MallCatalogPort.SkuView(
                101L, 11L, "MP001", "青瓷茶杯", "500ml", "/images/cup.jpg",
                9900L, 12900L, 4000L, 500L, 0L, 7L,
                3L, 4L, 5L, 1, true);
        when(catalogPort.findSkus(List.of(101L))).thenReturn(List.of(sku));

        MallCheckoutQuoteRequest request = new MallCheckoutQuoteRequest();
        MallCheckoutQuoteRequest.Item line = new MallCheckoutQuoteRequest.Item();
        line.setSkuId(101L);
        line.setQuantity(2);
        request.setItems(List.of(line));

        MallCheckoutQuoteDTO quote = service.quote(88L, request);

        assertThat(quote.getPayableAmount()).isZero();
        assertThat(quote.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getValid()).isFalse();
            assertThat(item.getReason()).isEqualTo("可用库存不足");
        });
    }

    void confirmReceiptCompletesOnceAndPublishesSettlementAndAccrual() {
        Order order = new Order();
        order.setId(200L);
        order.setOrderNo("MO001");
        order.setUserId(88L);
        order.setOrderType("MALL");
        order.setStatus(OrderService.STATUS_SHIPPED);
        order.setStoreSubjectId(3L);
        order.setChannelSubjectId(4L);
        order.setSplitRuleId(5L);
        order.setPaidAmount(19800L);
        when(orderMapper.selectOne(any())).thenReturn(order);

        OrderItem item = new OrderItem();
        item.setProductId("MP001");
        item.setProductName("青瓷茶杯");
        item.setUnitPrice(9900L);
        item.setQuantity(2);
        item.setSubTotal(19800L);
        item.setCostPrice(4000L);
        item.setPlatformCommission(500L);
        item.setSupplierSubjectId(7L);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item));

        service.confirmReceipt(88L, "MO001");

        verify(orderService).markMallCompleted(eq(order), any());
        verify(outboxService, times(2)).enqueue(anyString(), eq("MO001"), anyString(), anyString(), eq("MO001"), any());
    }
}

