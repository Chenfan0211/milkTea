package com.wuling.trade.internal;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wuling.trade.entity.Order;
import com.wuling.trade.entity.OrderItem;
import com.wuling.trade.mapper.OrderItemMapper;
import com.wuling.trade.mapper.OrderMapper;
import com.wuling.trade.service.OrderService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeInternalQueryControllerTest {

    @BeforeAll
    static void initMybatisPlusMetadata() {
        if (TableInfoHelper.getTableInfo(Order.class) == null) {
            MybatisConfiguration configuration = new MybatisConfiguration();
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, Order.class.getName());
            TableInfoHelper.initTableInfo(assistant, Order.class);
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void queueCountUsesCompletedStatusAsWorkInProgress() {
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
        TradeInternalQueryController controller =
                new TradeInternalQueryController(orderMapper, orderItemMapper);
        Order order = new Order();
        order.setId(1L);
        order.setStatus(OrderService.STATUS_COMPLETED);
        when(orderMapper.selectList(any())).thenReturn(List.of(order));
        OrderItem item = new OrderItem();
        item.setQuantity(3);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item));

        Map<String, Object> result = controller.storeQueueCount(9L);

        assertEquals(3L, result.get("count"));
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(orderMapper).selectList(captor.capture());
        captor.getValue().getTargetSql();
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(OrderService.STATUS_COMPLETED));
        assertFalse(captor.getValue().getParamNameValuePairs().containsValue("VERIFIED"));
    }
}