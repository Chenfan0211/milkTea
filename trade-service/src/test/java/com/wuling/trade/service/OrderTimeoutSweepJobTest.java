package com.wuling.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.wuling.common.outbox.OutboxService;
import com.wuling.trade.entity.Order;
import com.wuling.trade.mapper.OrderItemMapper;
import com.wuling.trade.mapper.OrderMapper;
import com.wuling.trade.pricing.MemberPricingService;
import com.wuling.trade.port.CouponPort;
import com.wuling.trade.port.ProductQueryPort;
import com.wuling.trade.port.SplitSnapshotQueryPort;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 门店订单「超时未支付自动关单兜底扫描」逻辑测试（P0）。
 *
 * <p>背景：MQ 延迟消息链路（outbox → RabbitMQ → 消费者）一旦断，
 * 未支付订单会永远停在 CREATED。这里加一个定时扫描兜底，复用
 * {@link OrderService#closeIfUnpaid(String)} 的行锁 + 幂等关单。
 */
class OrderTimeoutSweepJobTest {

    private OrderMapper orderMapper;
    private OrderService orderService;
    private OrderTimeoutSweepJob job;

    @BeforeAll
    static void initMybatisMetadata() {
        if (TableInfoHelper.getTableInfo(Order.class) == null) {
            MybatisConfiguration configuration = new MybatisConfiguration();
            MapperBuilderAssistant assistant =
                    new MapperBuilderAssistant(configuration, Order.class.getName());
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
                mock(CouponPort.class),
                mock(OutboxService.class),
                mock(MemberPricingService.class));
        job = new OrderTimeoutSweepJob(orderMapper, orderService, true);
    }

    private Order order(Long id, String status, String payStatus, LocalDateTime createTime) {
        Order o = new Order();
        o.setId(id);
        o.setOrderNo("WX" + id);
        o.setUserId(9L);
        o.setStoreSubjectId(1L);
        o.setStatus(status);
        o.setPayStatus(payStatus);
        o.setCreateTime(createTime);
        return o;
    }

    @Test
    @DisplayName("过期未支付门店订单被扫描关闭")
    void sweepsExpiredUnpaid() {
        LocalDateTime now = LocalDateTime.now();
        List<Order> expired = new ArrayList<>();
        Order o = order(1L, OrderService.STATUS_CREATED, "UNPAID", now.minusMinutes(20));
        expired.add(o);
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(expired);
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(o);
        when(orderMapper.updateById(any(Order.class))).thenReturn(1);

        int closed = job.sweep();

        assertEquals(1, closed, "应关闭 1 条过期未支付订单");
        verify(orderMapper).updateById(any(Order.class));
    }

    @Test
    @DisplayName("已支付订单绝不因扫描而关闭")
    void neverClosesPaid() {
        LocalDateTime now = LocalDateTime.now();
        List<Order> paid = new ArrayList<>();
        paid.add(order(2L, OrderService.STATUS_PAID, "PAID", now.minusMinutes(30)));
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(paid);

        int closed = job.sweep();

        assertEquals(0, closed, "已支付订单不得被关闭");
        verify(orderMapper, never()).updateById(any(Order.class));
    }

    @Test
    @DisplayName("扫描查询只锁定过期 CREATED + UNPAID 订单")
    void queryFiltersCorrectly() {
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        job.sweep();

        ArgumentCaptor<LambdaQueryWrapper<Order>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(orderMapper).selectList(captor.capture());
        captor.getValue().getTargetSql();
        assertTrue(
                captor.getValue().getParamNameValuePairs().containsValue(OrderService.STATUS_CREATED),
                "扫描必须过滤 status=CREATED");
        assertTrue(
                captor.getValue().getParamNameValuePairs().containsValue("UNPAID"),
                "扫描必须过滤 pay_status=UNPAID");
    }
}
